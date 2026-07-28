# Testing the endpoints

Four ways to exercise the API, from "already done for you" to "reachable by anyone,
anywhere". Pick the one that fits.

| Way | Effort | Who can use it |
|---|---|---|
| [1. Automated tests (CI)](#1-automated-tests-already-running) | none — already runs | anyone reading the repo |
| [2. Run locally + browser tester](#2-run-it-locally) | one command | you, on your machine |
| [3. Docker](#3-run-it-in-docker) | one build | you, on any machine with Docker |
| [4. Deploy publicly ("test anywhere")](#4-make-it-public--test-anywhere) | ~5 min, free host | anyone with the URL |

---

## 1. Automated tests (already running)

The endpoints are verified on **every push** by GitHub Actions — the full
integration suite boots the app and drives all three endpoints over HTTP. This is
the primary correctness proof; no setup needed.

- See it: the green check on each commit, or the **Actions** tab on GitHub.
- Run the same suite locally:
  ```bash
  ./mvnw test
  ```

> GitHub Actions **tests** the endpoints but does not **host** a live server —
> Actions is CI, not hosting. For a running server you can hit, use options 2–4.

---

## 2. Run it locally

```bash
./mvnw spring-boot:run          # starts on http://localhost:8080
```

Then either use the **built-in browser tester** or `curl`.

### Browser tester (easiest)

Open **<http://localhost:8080/>** — the app serves a small page with a form for
each endpoint and a "Run the spec's worked example" button. Because the page is
served by the app itself, it calls the API same-origin (no CORS setup needed).

### curl

```bash
BASE=http://localhost:8080

curl -X PUT $BASE/transactions/10 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"cars"}'                     # {"status":"ok"}
curl -X PUT $BASE/transactions/11 -H 'Content-Type: application/json' \
  -d '{"amount":10000,"type":"shopping","parent_id":10}'
curl -X PUT $BASE/transactions/12 -H 'Content-Type: application/json' \
  -d '{"amount":5000,"type":"shopping","parent_id":11}'

curl $BASE/transactions/types/cars                        # [10]
curl $BASE/transactions/sum/10                            # {"sum":20000.0}
curl $BASE/transactions/sum/11                            # {"sum":15000.0}
```

Error cases:

```bash
# missing parent -> 404
curl -i -X PUT $BASE/transactions/9 -H 'Content-Type: application/json' \
  -d '{"amount":1,"type":"x","parent_id":777}'
# invalid body (no amount) -> 400
curl -i -X PUT $BASE/transactions/9 -H 'Content-Type: application/json' -d '{"type":"x"}'
# self-parent / cycle on update -> 422 ; unknown id sum -> 404
curl -i $BASE/transactions/sum/999999
```

---

## 3. Run it in Docker

Same API, no JDK needed — only Docker:

```bash
docker build -t mendel-transactions .
docker run -p 8080:8080 mendel-transactions
```

Then use the browser tester at <http://localhost:8080/> or the `curl` commands above.

---

## 4. Make it public ("test anywhere")

To let reviewers test from anywhere, the app has to run on a public host. GitHub
Pages can't do this (it serves static files only, not a Java server), so deploy the
**container** to a free host. The repo already has a `Dockerfile` and a
`render.yaml`, so this is a few clicks.

### Recommended: Render (Docker, free, browser-based setup)

1. Go to <https://render.com> and sign in with GitHub.
2. **New +** → **Blueprint** → pick the `onembyte/mendel-challenge` repo.
3. Render reads `render.yaml`, builds the `Dockerfile`, and deploys. In ~2–4 min
   you get a public URL like `https://mendel-challenge.onrender.com`.
4. Test it from anywhere — same `curl` commands with `BASE` set to that URL, or just
   open the URL in a browser to get the tester page (served same-origin from the
   deployment, so no CORS).

> **Free-tier note:** the instance sleeps after ~15 min idle; the first request
> after sleep cold-starts in ~30–60s, then it's fast. Worth mentioning to reviewers
> so a slow first hit isn't mistaken for a bug.

### Alternatives

- **Railway** (<https://railway.app>): "Deploy from GitHub repo" → it detects the
  Dockerfile. Similar free allowance.
- **Fly.io** (<https://fly.io>): `fly launch` then `fly deploy` from the repo root
  (uses the Dockerfile; needs the `flyctl` CLI and an account).
- **Temporary tunnel for a live demo** (no account, ephemeral URL): run the app
  locally, then
  ```bash
  cloudflared tunnel --url http://localhost:8080
  ```
  and share the printed `*.trycloudflare.com` URL. Good for a screen-share; it
  disappears when you stop the process.

All of these honor the platform's `$PORT` because the app reads
`server.port=${PORT:8080}`.

### About GitHub Pages

You *can* host the tester **page** on GitHub Pages, but it would still need a public
backend to call (one of the deploys above) **and** CORS enabled on the app, since
the page's origin (`onembyte.github.io`) would differ from the API's. The bundled
same-origin tester (options 2–4) avoids all of that, so Pages isn't necessary — but
if you specifically want it, say so and I'll add a CORS config and a Pages workflow.

---

## Endpoint reference

| Method | Path | Body | Success |
|---|---|---|---|
| `PUT` | `/transactions/{id}` | `{"amount":double,"type":string,"parent_id":long?}` | `200 {"status":"ok"}` |
| `GET` | `/transactions/types/{type}` | — | `200 [ids…]` |
| `GET` | `/transactions/sum/{id}` | — | `200 {"sum":double}` |

Errors: `400` invalid/malformed body · `404` missing parent or unknown id ·
`422` self-parent or cycle.
