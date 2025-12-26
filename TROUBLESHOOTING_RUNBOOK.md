# Tasty ERP Troubleshooting Runbook (Docker + Caddy + Spring)

This document captures the real issues encountered while stabilizing this stack and the fixes applied, so the same failures can be diagnosed and prevented quickly in the future.

---

## 0) Architecture At A Glance

### Current (stable) request flow

```text
Browser (frontend)
   |
   |  http://localhost (:80)
   v
Caddy (edge reverse proxy)
   |
   +--> /api/waybills*  -> waybill-service:8081
   +--> /api/payments*  -> payment-service:8082
   +--> /api/config*    -> config-service:8888
   |
   `--> (everything else) -> frontend dev server (docker) OR static frontend
```

### Previously (problematic) request flow

```text
Browser -> Caddy -> Spring Cloud Gateway (api-gateway) -> services
```

Why it mattered: the browser was seeing transport-level failures while the backend logs showed successful processing, pointing to a proxying/streaming problem between gateway and client.

---

## 1) Symptom: `net::ERR_INCOMPLETE_CHUNKED_ENCODING 200 (OK)` from the browser

### What it means
The server *started* a response (HTTP 200), but the TCP connection ended before the client received a complete chunked body. This is almost always **network/proxy layer** (streaming) rather than application logic.

### Why logs can still look “successful”
Backend services can log “request processed” even if the connection is dropped after the service returns its response (or while the proxy streams it).

### Fix
**Re-architect ingress** so Caddy routes directly to microservices and bypasses Spring Cloud Gateway for external traffic.

**Prevention**
- Keep the data path as short as possible for large/chunked payloads.
- If a gateway is needed for auth/rate-limits, prefer: `Caddy -> Gateway -> services`, but ensure gateway timeouts, buffering, and streaming configuration are validated under load.

---

## 2) Symptom: 404 with body `Unknown API route` when calling `/api/...`

### What it means
That specific body is emitted by Caddy’s fallback handler in `Caddyfile`, so the request:
- reached Caddy
- did not match any configured API route

### Root causes encountered
1) **Path matcher mismatch**: routes like `/api/payments/*` do **not** match `/api/payments` (no trailing slash).
2) **Matcher ambiguity inside a single `handle`**: multiple `reverse_proxy <matcher>` directives can be easy to misread and hard to debug.

### Fix
- Use prefix matchers that cover both base and subpaths (`/api/payments*` etc.).
- Use explicit nested `handle` blocks for `/api/waybills*`, `/api/payments*`, `/api/config*`.

### Visual cue (matcher behavior)

```text
/api/payments      ❌ does NOT match /api/payments/*
/api/payments/123  ✅ matches /api/payments/*

/api/payments      ✅ matches /api/payments*
/api/payments/123  ✅ matches /api/payments*
```

---

## 3) Symptom: 502 Bad Gateway from Caddy for `/api/...`

### What it means
Caddy is up, but it cannot connect to the upstream service (DNS/network/port/service down).

### Root causes encountered
1) **Backend services not reachable on host ports**:
   - In dev compose, services publish `8081/8082/8888`.
   - In prod compose, services only `expose:` (no host ports), so `localhost:8081` will never work.
2) **Caddy not in the same network as services** (or services not started).
3) `host.docker.internal` not resolving inside Linux containers (fixed via `extra_hosts`).

### Fixes
- Ensure you’re using the correct compose file for your run mode:
  - Local dev: `docker-compose.dev.yml` (ports published)
  - Prod-style: `compose.yml` (internal networking; no host ports)
- Add `extra_hosts: ["host.docker.internal:host-gateway"]` for portability.
- Add upstream fallbacks in Caddy (container DNS + host gateway).

### Quick diagnosis checklist

```text
1) Is Caddy running?
   curl -i http://localhost/health

2) Can host reach services?
   curl -i http://localhost:8081/actuator/health
   curl -i http://localhost:8082/actuator/health
   curl -i http://localhost:8888/actuator/health

3) If #2 fails:
   - services are down OR you started prod compose OR Docker isn’t running
```

---

## 4) Symptom: Browser shows `net::ERR_CONNECTION_REFUSED` for `http://localhost/api/...`

### What it means
Nothing is listening on that host:port (or firewall blocks it). This is **not CORS**.

### Root cause encountered
Frontend was configured to call an absolute URL `http://localhost/api`, so the browser depended on port 80 being live.

### Fix
In dev, set the frontend API base to a relative path:

```env
VITE_API_URL=/api
```

Then Vite proxies `/api` to Caddy, and the browser does not do cross-origin calls.

---

## 5) Symptom: Spring Boot fails at startup with placeholder resolution stack traces

Example stack fragment:
```text
at org.springframework.core.env.AbstractPropertyResolver.resolveNestedPlaceholders(...)
```

### What it means
Spring encountered `${SOME_ENV_VAR}` that was required but not provided.

### Root causes encountered
- Services expect env vars like `FIREBASE_PROJECT_ID`, `SOAP_SU`, `SOAP_SP`.
- Running from IDE/CLI without those env vars set causes startup failure.

### Fix
Allow services to auto-load the repo `.env` file when running locally:

```yaml
spring:
  config:
    import: "optional:file:../.env[.properties],optional:file:.env[.properties]"
```

### Prevention
- Prefer defaults for non-secret values.
- Fail fast with clear error messages for secrets.
- Document required env vars and provide `.env.example`.

---

## 6) Symptom: Docker build fails with `COPY target/*.jar` (no such file)

### What it means
The Dockerfile expects a JAR already built on the host (`target/*.jar`), but it doesn’t exist.

### Fix
Use multi-stage Docker builds that compile inside the image:

```text
FROM maven:... AS build
RUN mvn -pl <service> -am package

FROM eclipse-temurin:...
COPY --from=build .../target/*.jar app.jar
```

### Prevention
- Prefer reproducible container builds (build inside Docker).
- Avoid relying on host-side `target/` being present.

---

## 7) Symptom: Docker build fails with huge context and Windows “invalid file request” (node_modules)

### What it means
Docker tried to include `node_modules` (and other build outputs) in the build context, and Windows filesystem quirks caused failures.

### Fix
Add a root `.dockerignore` to exclude `node_modules`, `dist`, `target`, etc.

### Visual: build context size

```text
Without .dockerignore  -> hundreds of MB / can fail
With .dockerignore     -> small context / reliable builds
```

---

## 8) Symptom: `docker ... Access is denied` to `dockerDesktopLinuxEngine` pipe

### What it means
Your shell user lacks permission to talk to the Docker Desktop engine on Windows.

### Fix / Prevention
- Ensure Docker Desktop is running.
- Add your Windows user to the `docker-users` group and re-login.
- Or run PowerShell as Administrator when needed.

---

## 9) Symptom: Local `npm` is broken (`Cannot find module ... walk-up-path`)

### What it means
You have multiple npm installs on PATH, and the active one is corrupted.

### Root cause encountered (PATH shadowing)

```text
C:\Program Files\nodejs\npm.cmd           (good)
C:\Users\Admin\AppData\Roaming\npm\npm.cmd (broken, takes precedence in some shells)
```

### Fix / Prevention
- Remove `C:\Users\<user>\AppData\Roaming\npm` from PATH if it is broken.
- Prefer a single managed Node installation (Node 20 LTS recommended for tooling stability).
- Alternative: run frontend in Docker (removes local npm dependency).

---

## 10) VAT calculation correctness: missing nested waybills

### Symptom
Some waybills present in RS.ge responses were not counted in VAT totals.

### Root cause
RS.ge SOAP responses can contain **duplicate waybill IDs**:
- one shallow representation
- one nested/detailed representation

If the extractor dedupes with “first wins”, it can keep the shallow entry (missing amount fields), causing:
- amount parsed as `0`
- excluded from totals

### Fix
When deduping by ID, prefer the **richer** map (more complete fields), and expand the amount field list to include alternate keys (GROSS/NET/VALUE/SUMA/etc.).

### Visualization: “first wins” vs “richer wins”

```text
Response contains:
  ID=123 (shallow)  -> no FULL_AMOUNT
  ID=123 (nested)   -> FULL_AMOUNT=1000

First-wins dedupe:
  keeps shallow -> amount=0 -> VAT missing

Richer-wins dedupe:
  keeps nested  -> amount=1000 -> VAT correct
```

---

## Operational “Reset & Rebuild” (Safe Commands)

Always run from repo root:
`C:\Users\Boris\Dell\Projects\APPS\ERP\Tasty_erp_new`

### Dev stack rebuild

```powershell
docker-compose -f docker-compose.dev.yml down --remove-orphans -v
docker builder prune -af
docker-compose -f docker-compose.dev.yml build --no-cache
docker-compose -f docker-compose.dev.yml up -d --force-recreate
```

### Health checks

```powershell
curl -i http://localhost/health
curl -i http://localhost:8081/actuator/health
curl -i http://localhost:8082/actuator/health
curl -i http://localhost:8888/actuator/health
```

---

## “When X Happens, Check Y” Cheat Sheet

| Symptom | Usually means | First check |
|--------|----------------|------------|
| `ERR_INCOMPLETE_CHUNKED_ENCODING 200` | proxy/streaming drop | simplify ingress path; check proxy logs/timeouts |
| `Unknown API route` | Caddy route mismatch | Caddyfile matchers (`/api/foo*`) |
| `502 Bad Gateway` | upstream unreachable | service health on expected port/network |
| `ERR_CONNECTION_REFUSED` | nothing listening | is Caddy running on `:80`? |
| Spring placeholder stacktrace | missing env var | `.env` present + `spring.config.import` |
| Docker `COPY target/*.jar` fails | JAR not built | multi-stage build or run Maven first |
| Docker context errors include node_modules | missing `.dockerignore` | add root `.dockerignore` |
| npm “cannot find module …” | broken npm on PATH | `where.exe npm` and remove broken one |

