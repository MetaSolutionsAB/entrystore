# EntryStore Investigation — REST Auth & Proxy (D10; B4/D6/G5 decisions)

Date: 2026-07-13
Module: `core/core-impl`, `modules/rest/spring-boot`
Branch: `feature/benchmark-ai`

Theme covers findings D10, B4, D6 and G5. As with doc 15 these are HTTP
request-path items the write/read benchmark cannot A/B; the gate is the
integration-test suite plus reasoning.

## Implemented

### D10 — stop double-hashing the submitted password on every login

`CheckUsernamePasswordFilter` validated the submitted password with
`Password.check(password, Password.getSaltedHash(password))`. That runs
**two PBKDF2 hashes** (each tuned to ~10 ms) per login attempt — and the
comparison is a tautology (hashing X then verifying X against hash(X) is
always true). Its only real effect was to reach
`checkMinimumRequirements`, which throws `IllegalArgumentException` for an
empty or over-long password.

New `Password.validateFormat(String)` performs exactly that check with
**no** PBKDF2. The filter now calls it. This removes ~20 ms of CPU per
login attempt and, importantly, removes a credential-stuffing CPU
amplifier: an attacker could previously force two PBKDF2 computations per
guessed password before authentication. The check only ever touched the
**submitted** password (never the stored secret or user existence), so
there is no timing side-channel about valid users — the change preserves
that property while cutting the cost.

Gate: `PasswordTest` (8) green; login/credential ITs green.

## Deferred (with rationale)

### B4 — per-request user reload / short-TTL UserDetails cache

`ReloadUserPropertiesFilter` + `ESUserDetailsService.loadUserByUsername`
reload the user from RDF4J on every authenticated request. A short-TTL
cache (the finding suggests 1–5 s) would remove that, but it is
**auth-sensitive**: within the TTL a disabled user, a permission change,
or a logout would not yet be observed. Correct behaviour requires
explicit invalidation on logout, disable and password change, plus an IT
that pins the staleness window. Given the B-theme auth refactor already
landed this round (doc 14, gated by 759 ITs), stacking a second auth
cache in the same round raises regression risk disproportionately. It
should be a focused, separately-reviewed change. The current per-request
reload is the correctness-conservative behaviour, so deferring leaves the
system correct, just not as fast on the authenticated-request path.

### D6 — proxy buffering / pooled HTTP client

The doc-08 concern was "buffers up to 10 MB ×3 copies; `disconnect()`
kills keep-alive". Inspecting the current `ProxyService`: it already
enforces `MAX_RESPONSE_BYTES = 10 MB` via `readWithLimit`, which streams
into a single `ByteArrayOutputStream` and throws once the cap is exceeded
— so the size cap and the multiple-copy buffering are **already
addressed** (by earlier hardening on this branch). The one remaining
item is that `conn.disconnect()` prevents keep-alive, so a fresh TLS
handshake is paid per upstream request/redirect hop. Migrating to a
pooled JDK `HttpClient` would enable connection reuse, but the proxy is
SSRF-sensitive (redirect handling, host validation) and already
IT-covered (`ProxyIT`, 25 tests); a full client migration is a moderate
rewrite whose only benefit is keep-alive to repeated hosts. Deferred as
its own change rather than risking the working, size-capped proxy here.

### G5 — virtual-thread password-reset executor

The finding is explicit that this is "simplification only — no perf
gain". The current `AuthService` password-reset executor is a
`ThreadPoolExecutor` with a deliberately hardened `ThreadFactory` (it
prevents an escaping `Error` from being silently swallowed) and an
orderly shutdown path. Replacing it with
`newVirtualThreadPerTaskExecutor` would delete that intentional hardening
for a cosmetic reduction on a tiny, rate-limited traffic path. Not worth
the churn/risk; left as-is.

## Correctness

`PasswordTest` (8, core) green; the login / credential-confirmation /
signup / password ITs green (they exercise
`CheckUsernamePasswordFilter`).

## How to reproduce (behaviour)

Login-path timing needs a running instance and load; verified via the IT
suite. Run: `./mvnw clean verify -pl modules/rest/integration-test`.
