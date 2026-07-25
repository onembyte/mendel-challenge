# Project Walkthrough & Design Decisions

A step-by-step narrative of how this service was built and **why** each decision
was made. It complements the [README](../README.md) (which is reference
documentation) with the reasoning behind the code — the material you'd talk
through in a design review or interview.

The guiding principle throughout: **the specification is the contract.** Every
decision was checked against the spec's exact wording and its worked example, and
where the spec was silent I made an explicit, documented choice rather than an
accidental one.

---

## Table of contents

1. [Reading the problem](#1-reading-the-problem)
2. [Choosing the stack](#2-choosing-the-stack)
3. [Step 0 — Project skeleton, repository, CI](#3-step-0--project-skeleton-repository-ci)
4. [Step 1 — The storage core (domain + repository)](#4-step-1--the-storage-core-domain--repository)
5. [Step 2 — The PUT endpoint](#5-step-2--the-put-endpoint)
6. [Step 3 — GET by type](#6-step-3--get-by-type)
7. [Step 4 — GET sum (the interesting one)](#7-step-4--get-sum-the-interesting-one)
8. [Step 5 — End-to-end acceptance test](#8-step-5--end-to-end-acceptance-test)
9. [Step 6 — Dockerization](#9-step-6--dockerization)
10. [Cross-cutting: how it was tested](#10-cross-cutting-how-it-was-tested)
11. [Challenges I hit (and how I diagnosed them)](#11-challenges-i-hit-and-how-i-diagnosed-them)
12. [What I'd do with more time](#12-what-id-do-with-more-time)

---

## 1. Reading the problem

Before writing any code I extracted the exact contract from the spec, including
the parts that are easy to skim past:

- **Three endpoints**, and the response shapes are precise: `PUT` returns
  `{"status":"ok"}`, `GET types` returns a **bare JSON array** `[10, 11]` (not an
  object), `GET sum` returns `{"sum": <double>}`.
- **The wire key is `parent_id`** (snake_case) in both the body spec (Codigo 1)
  and the example (Codigo 4). Java convention is `parentId`. That mismatch is a
  trap — see step 2.
- **`sum` is transitive and includes the transaction itself.** I confirmed this by
  recomputing the example by hand: `10 (5000) ← 11 (10000) ← 12 (5000)` gives
  `sum/10 = 20000` (all three) and `sum/11 = 15000` (11 + 12). So "sum" means
  "this node's amount + every descendant's amount".
- **Constraints:** in-memory storage, no SQL, Java 11+, integration tests,
  dockerized, code clarity, correct architecture. **Valued:** TDD, incremental
  commits, SOLID, documentation.

> **Decision:** treat the worked example as an executable acceptance test (it
> became `FullFlowIntegrationTest`), so "did I read the spec correctly?" is
> answered by a green test, not by my memory.

---

## 2. Choosing the stack

**Java 21 + Spring Boot 4.1 + Maven (wrapper).**

- **Java 21** — the latest LTS. Satisfies "Java 11+" with margin and gives records
  (used for the immutable domain model and DTOs) and modern collection factories.
- **Spring Boot 4.1** — this is a **forced, deliberate** choice worth explaining:
  start.spring.io **no longer offers Boot 3.x** (a request for 3.x returns HTTP
  400: "compatibility range is >= 4.0.0"). Rather than pin an unavailable version,
  I targeted 4.1 and dealt with its migration surface explicitly (Jackson 3, the
  test-client changes — see §11). The spec only requires "Spring Boot", so this is
  compliant.
- **Maven with the wrapper (`./mvnw`)** — so a reviewer needs nothing installed but
  a JDK; the build is reproducible without a local Maven. Maven over Gradle simply
  because it's the most boring, most universally readable choice for this kind of
  service.

> **Trade-off:** using the newest Boot meant hitting undocumented-for-me migration
> details. I judged that authenticity (a project that builds on today's tooling)
> plus the chance to demonstrate diagnosis was worth more than pinning an old,
> comfortable version — and I pinned `bootVersion=4.1.0` so the build stays
> reproducible.

---

## 3. Step 0 — Project skeleton, repository, CI

**Bootstrapped via the Spring Initializr HTTP API** (not the website) so the exact
parameters are captured as a repeatable command:

```bash
curl … https://start.spring.io/starter.tgz \
  -d bootVersion=4.1.0 -d packageName=com.mendel.transactions -d dependencies=web,validation …
```

Two non-obvious flags:

- **`packageName=com.mendel.transactions` is required.** Without it, Initializr
  derives the package from the artifactId `transactions-service` and produces
  `com.mendel.transactions_service` — a subtly wrong package. I pinned it.
- **`dependencies=web,validation`** — `web` for the REST layer, `validation` so
  Bean Validation (`@NotNull`, `@NotBlank`) is available for the request DTO.

**CI was added second, before any feature code.** A ten-line GitHub Actions
workflow (`checkout` → `setup-java 21` → `./mvnw -B test`) runs on every push.

> **Decision:** wire CI up front, not at the end. GitHub Actions only runs
> workflows that exist *at the time of the push*, so adding it early means every
> subsequent commit gets a green check — the reviewer sees a continuously-green
> history, which is a much stronger signal than one green run at the tip.

I also cleaned the generated `pom.xml` (removed the empty `<licenses>`,
`<developers>`, `<scm>` boilerplate Initializr leaves behind) — small, but code
clarity is explicitly graded.

---

## 4. Step 1 — The storage core (domain + repository)

This is the heart of the service, so it was built first and test-first.

**`Transaction` is an immutable `record`** with a nullable `Long parentId` (a
parentless transaction is a root). A record gives immutability, value equality,
and a compact constructor (used to reject a null `type`) for free.

**A `TransactionRepository` interface with an `InMemoryTransactionRepository`
implementation.** This is the SOLID/DIP core: the service depends on the
*interface*. If "no SQL, in memory is fine" ever became "now use Postgres", only
one class changes and nothing above it does.

**Two secondary indexes, maintained on write:**

| Structure | Purpose |
|---|---|
| `store: id → Transaction` | primary source of truth |
| `typeIndex: type → Set<id>` | `GET types` is a direct lookup, not a scan |
| `childrenIndex: parentId → Set<id>` | `GET sum` walks only a subtree, not everything |

> **Decision — pay on write, not on read.** Maintaining indexes makes writes do a
> little more work so that both reads are cheap and simple. For a service whose
> entire reason to exist is those two queries, that's the right balance.

**The single most important design decision — a `synchronized` write path:**

`ConcurrentHashMap` is atomic *per map*, but an upsert touches *three* maps, and
the cycle check is *check-then-act*. Those are exactly the things a per-map
guarantee does **not** cover. Two concrete races:

1. Two concurrent updates to the same id could interleave and leave a **permanent**
   stale entry in `typeIndex`/`childrenIndex`.
2. Two concurrent links `A→B` and `B→A` could each pass an independent cycle check
   and then both commit, creating a real cycle.

So **all writes go through one `synchronized upsert(...)`**. Inside the lock, in
order: check the parent exists, reject self-parent and cycles (walk the ancestor
chain), remove the id from its *old* index buckets, add it to the new ones, then
put it in the store. **Reads stay lock-free** on the concurrent maps.

> **Decision — invariants live in the repository, not the service.** Parent-exists
> and acyclicity are *storage* invariants. If the service checked "does the parent
> exist?" and then called the repository to write, that check-then-act would race
> across the layer boundary. Enforcing them *inside* the locked write is the only
> place they're actually safe. The repository throws domain exceptions
> (`TransactionNotFoundException`, `InvalidTransactionException`); mapping those to
> HTTP is the web layer's job.

> **Honest limitation (I'd rather state it than hide it):** because reads are
> lock-free, a read can transiently observe an in-flight write. Every *completed*
> write leaves the store consistent, and the sum traversal's `visited` set keeps it
> safe regardless. Full read-snapshot isolation is deliberately out of scope for an
> in-memory challenge — but I know exactly where the boundary is.

**Type listings are returned sorted ascending.** `Set` iteration order is
undefined; the spec doesn't require an order, but returning a *defined* one turns
a potentially flaky contract into a deterministic one — and lets tests assert an
exact array.

---

## 5. Step 2 — The PUT endpoint

Thin controller → service → repository. The controller only routes and maps DTOs;
the service maps request→domain and delegates; the repository does the work.

**The request DTO is where the subtle spec-compliance lives:**

```java
public record TransactionRequest(
    @NotNull Double amount,
    @NotBlank String type,
    @JsonProperty("parent_id") Long parentId) {}
```

Two decisions that each prevent a *silent* bug:

- **`@JsonProperty("parent_id")`** binds the snake_case wire key. Without it,
  Jackson silently ignores the unknown `parent_id` key, `parentId` stays null,
  every transaction becomes a root, and `sum/10` would return `5000` instead of
  `20000` — **with no error anywhere**. I chose a field-level annotation over a
  global snake_case strategy because only this one field deviates; every other
  contract field (`amount`, `type`, `sum`, `status`) is a single word.
- **Wrapper types (`Double`, `Long`), not primitives.** A primitive `double amount`
  would deserialize a *missing* field to `0.0`, `@NotNull` on a primitive is
  meaningless, so the promised `400` never fires and a bogus zero-amount
  transaction is stored. A primitive `long parentId` would turn every parentless
  request into a link to transaction `0`.

**Error codes — the spec prescribes none, so I committed to one per case** and
documented the reasoning:

| Case | Status | Why |
|---|---|---|
| missing parent / unknown id | **404** | the referenced entity doesn't exist |
| self-parent or cycle | **422** | body is well-formed but the graph op is invalid |
| missing/blank field, malformed JSON | **400** | the request itself is invalid |

A `@RestControllerAdvice` maps exceptions to these codes with a consistent error
body. `422` (rather than another `400`) is the meaningful distinction: it tells the
client "I understood your request; the *operation* is the problem."

> **Detail worth noting:** self-parent and cycles are only reachable through an
> *update*. On create, the parent must already exist, so a self-reference collapses
> into the missing-parent `404`; the `422` path is genuinely reachable only when
> re-pointing an existing transaction. The tests exercise it that way on purpose.

---

## 6. Step 3 — GET by type

The smallest endpoint: return the repository's ascending id list as a **bare JSON
array**, empty array (`200`) for an unknown type. The only decision of substance —
sorting — was already made in the storage layer (§4), which is why the multi-id
test can assert an exact `[11, 12]` without flakiness.

---

## 7. Step 4 — GET sum (the interesting one)

The sum is an **iterative depth-first traversal** over `childrenIndex`, starting
from the target and accumulating amounts, guarded by a `visited` set:

```java
double sum(long id):
    if id not present -> 404
    push id; while stack not empty:
        pop current; if already visited -> skip
        total += amount(current)
        push each child of current
    return total
```

Two decisions, each defending against a failure mode:

- **Iterative, not recursive.** A recursive traversal would blow the stack on a
  long parent chain. There is a **10,000-deep chain test** specifically to prove
  the iterative version handles depth a recursive one couldn't.
- **`visited` set.** The write path already guarantees an acyclic graph, but the
  traversal defends itself anyway — cheap insurance that also bounds the work.

> **Decision — traverse at read time rather than precompute.** I could keep a
> running per-node total and update ancestors on every write. That makes `sum`
> O(1) but makes every write O(depth) and far more error-prone (every re-parent
> has to fix up two ancestor chains). For an in-memory service where reads and
> writes are both cheap, read-time traversal is simpler and obviously correct —
> the right trade for this problem. I'd revisit it only under a measured
> read-heavy load.

**On the response shape:** the spec types `sum` as `double`, so the JSON is
`{"sum":20000.0}` where the example prints `20000`. Those are numerically equal.
Every test asserts the **numeric value**, never the string — so a serialization
detail can't cause a false failure. This is also why the integration tests parse
JSON rather than compare raw response strings.

---

## 8. Step 5 — End-to-end acceptance test

`FullFlowIntegrationTest` replays the spec's worked example verbatim over HTTP:
create the three transactions, then assert `types/cars == [10]`, `sum/10 == 20000`,
`sum/11 == 15000`. It's the closing proof that all the parts compose correctly —
and `sum/10 == 20000` is itself end-to-end proof that the snake_case `parent_id`
binding works through real JSON, not just in a unit test.

---

## 9. Step 6 — Dockerization

A **multi-stage** build:

- **Build stage** (`maven:3.9-eclipse-temurin-21`): copy `pom.xml` first and run
  `dependency:go-offline`, *then* copy `src` and package. Because Docker caches
  layers, this means a source-only change doesn't re-download the entire
  dependency tree — only the fast recompile re-runs.
- **Runtime stage** (`eclipse-temurin:21-jre`): a JRE-only image (smaller, no build
  tools in the shipped artifact), running as a **non-root user**, exposing `8080`.

A `.dockerignore` keeps `.git`, `target/`, and IDE files out of the build context.
I verified the image by building it and replaying the spec example with `curl`
against the running container — including the `404`/`400`/`422` error cases.

> **Decision — selective `COPY`, never `COPY . .`.** Copying only `pom.xml` then
> `src/` guarantees a stale host `target/` can't leak into the image and keeps the
> dependency layer cacheable.

---

## 10. Cross-cutting: how it was tested

**TDD with an auditable git history.** Each feature is two commits: `test: … (red)`
adds failing tests plus compiling stubs (so the suite fails on *assertions*, not
compilation), then `feat: … (green)` is the minimal implementation. `git log`
therefore *shows* the red→green rhythm — a single squashed "test+feat" commit
would be indistinguishable from writing tests afterward.

**Two test styles, chosen deliberately:**

- **Unit tests** for the repository (plain JUnit + AssertJ, no Spring) — fast, and
  they pin the storage invariants directly.
- **Integration tests** boot the whole app on a random port and drive it over
  **real HTTP** (`TestRestTemplate`). Deliberate choices here:
  - **Send raw JSON strings**, not serialized project DTOs. If a test serialized the
    same DTO the server deserializes, a `parentId`-vs-`parent_id` mismatch would
    round-trip symmetrically and hide. Raw JSON matching the spec's wire format
    can't.
  - **Assert on parsed JSON** (JSONPath), not string equality — robust to
    formatting and to `20000` vs `20000.0`.
  - **One shared base class** carries `@SpringBootTest` and clears the store in
    `@BeforeEach`. Because every test shares one context configuration, Spring boots
    the server **once** for the whole suite (fast), while each test still starts from
    an empty store. I specifically avoided `@DirtiesContext`, which would rebuild the
    context per class and be much slower.

---

## 11. Challenges I hit (and how I diagnosed them)

These are real problems the newest Spring Boot threw at me. Each was diagnosed from
the actual error, not guessed:

1. **Boot 3.x is gone.** Initializr rejects `bootVersion=3.x`. I queried its
   metadata endpoint, saw only `4.0.7`/`4.1.0` offered, and targeted 4.1.
2. **`TestRestTemplate` didn't exist as a bean.** First integration run failed with
   a context-load error. Root cause (from the stack trace):
   `NoClassDefFoundError: RestTemplateBuilder`. In Boot 4, classic `RestTemplate`
   support was extracted into a separate `spring-boot-restclient` module that the
   web starter no longer pulls in, **and** the `TestRestTemplate` bean is now gated
   behind an explicit `@AutoConfigureTestRestTemplate`. Fix: add the module (test
   scope) + the annotation. I found both by inspecting the jar's autoconfigure
   metadata before writing more code.
3. **`HttpStatus.UNPROCESSABLE_ENTITY` no longer matched.** A 422 assertion failed
   with `expected UNPROCESSABLE_ENTITY but was UNPROCESSABLE_CONTENT` — Spring
   Framework 7 renamed the 422 constant (RFC 9110 wording). The code is identical;
   I switched to the canonical constant and now assert 422 **numerically** so a
   future rename can't break it.
4. **Jackson 3.** Boot 4 ships Jackson 3 (`tools.jackson.*`), but `@JsonProperty`
   still comes from `com.fasterxml.jackson.annotation` — so the annotation import is
   unchanged, but any manual JSON handling would use the new package. I used JSONPath
   in tests to sidestep it entirely.

> The takeaway I'd highlight: none of these were guessed. Each came from reading the
> actual failure, confirming the mechanism (jar contents, metadata endpoint, stack
> trace), then making the minimal fix — and each is documented in the code near
> where it matters.

---

## 12. What I'd do with more time

Scoped intentionally — I stopped at "correct, clear, well-tested" rather than
gold-plating a challenge:

- **OpenAPI/Swagger UI** via springdoc — interactive API docs for near-zero cost.
- **A persistence adapter** — the `TransactionRepository` interface already makes
  this a drop-in; a JPA or Redis implementation would need no changes above it.
- **Observability** — Actuator health/metrics, and a structured access log.
- **Property-based tests** for the sum over randomly-generated DAGs, to complement
  the hand-picked example and branching-tree cases.
- **Concurrency stress test** — hammer concurrent upserts on the same id and assert
  no index ever goes stale, turning the reasoning in §4 into an executable guarantee.
