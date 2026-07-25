# Transactions Service — Mendel Java Code Challenge

[![CI](https://github.com/onembyte/mendel-challenge/actions/workflows/ci.yml/badge.svg)](https://github.com/onembyte/mendel-challenge/actions/workflows/ci.yml)

A RESTful service that stores transactions **in memory** and answers questions
about them: list ids by type, and total the amount of a transaction together
with everything transitively linked to it through `parent_id`.

- **Stack:** Spring Boot 4.1, Java 21, Maven (wrapper included). No database, no SQL.
- **Tests:** JUnit + AssertJ unit tests for the storage core, plus real-HTTP
  integration tests for every endpoint.
- **Runs anywhere:** dockerized (multi-stage build, non-root runtime).

> A step-by-step account of how it was built and **why** each decision was made
> is in [`docs/WALKTHROUGH.md`](docs/WALKTHROUGH.md).

## API

Base path: `/transactions`. Bodies and responses are JSON.

| Method & path | Request body | Success response | Notes |
|---|---|---|---|
| `PUT /transactions/{id}` | `{ "amount": double, "type": string, "parent_id": long? }` | `200` `{"status":"ok"}` | Create or replace the transaction with `id` (upsert). `parent_id` is optional. |
| `GET /transactions/types/{type}` | — | `200` `[10, 11, ...]` | Ids of all transactions of `type`, ascending. Empty array if none. |
| `GET /transactions/sum/{id}` | — | `200` `{"sum": double}` | `id`'s amount plus all transitive descendants. |

### Example (the challenge's worked example)

```bash
curl -X PUT localhost:8080/transactions/10 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"cars"}'                        # {"status":"ok"}
curl -X PUT localhost:8080/transactions/11 -H 'Content-Type: application/json' \
  -d '{"amount":10000,"type":"shopping","parent_id":10}'    # {"status":"ok"}
curl -X PUT localhost:8080/transactions/12 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"shopping","parent_id":11}'     # {"status":"ok"}

curl localhost:8080/transactions/types/cars                 # [10]
curl localhost:8080/transactions/sum/10                     # {"sum":20000.0}
curl localhost:8080/transactions/sum/11                     # {"sum":15000.0}
```

### Error responses

The spec does not prescribe error codes; these are deliberate design choices,
returned with a consistent body `{"status":int,"error":string,"message":string}`.

| Case | Status | Rationale |
|---|---|---|
| `parent_id` references a missing transaction; sum of an unknown id | `404 Not Found` | The referenced transaction does not exist. |
| A transaction set as its own parent, or a link that would create a cycle | `422 Unprocessable Content` | The body is well-formed but the graph operation is invalid. |
| Missing/blank `amount` or `type`, or malformed JSON | `400 Bad Request` | The request itself is invalid. |

## Running it

Requires JDK 21 (for the local commands) and/or Docker. The Maven wrapper
(`./mvnw`) is bundled, so Maven need not be installed.

```bash
# Run the tests
./mvnw test

# Run the service (listens on :8080)
./mvnw spring-boot:run

# Build and run in Docker
docker build -t mendel-transactions .
docker run -p 8080:8080 mendel-transactions
```

## Architecture

A thin, layered design so each concern is testable in isolation and storage is
swappable (Dependency Inversion — the service depends on the
`TransactionRepository` interface, not the in-memory implementation).

```
com.mendel.transactions
├── controller/   TransactionController      REST layer: routing + DTO mapping only
├── service/      TransactionService         orchestration, request→domain mapping, sum traversal
├── repository/   TransactionRepository      storage abstraction (interface)
│                 InMemoryTransactionRepository  concurrent-map implementation
├── model/        Transaction                immutable domain record
├── dto/          TransactionRequest, StatusResponse, SumResponse, ErrorResponse
└── exception/    TransactionNotFoundException, InvalidTransactionException,
                  GlobalExceptionHandler     maps exceptions → HTTP status + body
```

### Storage and the two indexes

`InMemoryTransactionRepository` keeps a primary `id → Transaction` map plus two
secondary indexes maintained on write, so reads stay cheap:

- **`typeIndex`** (`type → ids`) answers "list by type" directly.
- **`childrenIndex`** (`parentId → child ids`) lets the sum walk only a
  transaction's subtree instead of scanning every transaction.

### Sum: read-time traversal

`sum(id)` is an **iterative** DFS over `childrenIndex` guarded by a `visited`
set. Iterative (not recursive) so an arbitrarily deep chain can't overflow the
stack — there's a 10,000-deep test for exactly this — and `visited` bounds the
work and is robust to any cycle. Doing the traversal at read time keeps writes
O(1) and simple; precomputing ancestor aggregates would speed up sum reads but
complicate every write, which isn't the right trade for this challenge.

### Concurrency

`ConcurrentHashMap` is atomic per operation but not across the three maps, and
the parent-exists / cycle checks are check-then-act. So **all writes go through a
single `synchronized upsert`**: an update re-indexes type and parent atomically
(no stale entries), and the parent-exists (404) and self-parent/cycle (422)
invariants are enforced inside the lock as storage invariants. Reads stay
lock-free on the concurrent structures. The honest limit: a lock-free read may
transiently observe an in-flight write; every *completed* write leaves the store
consistent, and the sum's `visited` set keeps traversal safe regardless. Full
read-snapshot isolation is intentionally out of scope.

### Notable design decisions

- **`PUT` is an upsert.** `PUT` targets a specific id and is idempotent; a repeat
  replaces the transaction and re-indexes it. The spec defines no duplicate-id
  error, so replacement is the natural, RESTful behavior.
- **Self-parent and cycles are only reachable via updates.** At create time the
  parent must already exist, so a self-reference collapses into the missing-parent
  `404`; the `422` cases are exercised through the update path in the tests.
- **`sum` renders as a double.** The spec types `sum` as `double`, so a whole
  total serializes as `20000.0` — numerically equal to the spec's `20000`. Tests
  assert the numeric value, not the string.

### Spring Boot 4 specifics

Boot 4 moved a few things that this project accounts for:

- **`parent_id` (snake_case) ↔ `parentId` (camelCase).** Mapped explicitly with
  `@JsonProperty("parent_id")` on the request DTO — only this one field deviates,
  so a field-level annotation is cleaner than a global naming strategy. Jackson
  silently ignores unknown keys, so the sum tests double as proof the link
  actually binds (`sum/10 == 20000`).
- **Wrapper types in the request DTO.** `amount`/`parentId` are boxed (`Double`,
  `Long`), not primitives: a primitive `amount` would default a missing value to
  `0.0` and make `@NotNull` unenforceable.
- **`TestRestTemplate`** needs `@AutoConfigureTestRestTemplate` (no longer
  provided by `@SpringBootTest` alone) and the `spring-boot-restclient` module on
  the test classpath (classic `RestTemplate` was extracted from the web starter).

## Testing & TDD

The core was built test-first. The git history shows the rhythm as **`test: … (red)`
→ `feat: … (green)`** commit pairs: the red commit adds failing tests with
compiling stubs; the green commit is the minimal implementation that passes.

- **Unit tests** (`InMemoryTransactionRepositoryTest`) cover the storage core:
  indexing, atomic re-indexing on update, and the graph invariants.
- **Integration tests** boot the full app on a random port and drive it over real
  HTTP via `TestRestTemplate`. They send **raw JSON bodies** (not serialized
  project DTOs, which would mask a `parent_id` naming mismatch) and assert on
  parsed JSON. A shared base class clears the store before each test, so the
  server boots once for the whole suite while every test starts empty.
- `FullFlowIntegrationTest` replays the spec's worked example end-to-end.
