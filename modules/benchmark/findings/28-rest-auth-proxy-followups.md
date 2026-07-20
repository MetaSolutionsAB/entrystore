# Finding 28: REST auth/proxy follow-ups — UserDetails TTL cache, proxy keep-alive, virtual threads (ENTRYSTORE-1090)

Date: 2026-07-20
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1090](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1090)

## What was changed

- **B4 — per-request UserDetails cache.** `ReloadUserPropertiesFilter` re-resolves the
  authenticated user on every request via `ESUserDetailsService.loadUserByUsername` (admin-elevated
  `getPrincipalEntry` + secret + role resolution). A TTL cache
  (`entrystore.auth.userdetails-cache.ttl-seconds`, default 5, `0` = off) now serves the immutable
  identity snapshot; a fresh mutable `ESUserSessionDetails` wrapper is still minted per call.
  **Eviction, not TTL expiry, is the staleness guarantee:** a `RepositoryListener` clears the cache
  on any `ResourceUpdated`/`EntryUpdated`/`RelationsUpdated`/`EntryDeleted` whose source is a User
  or Group entry — core fires these synchronously and post-commit from `setSaltedHashedSecret`,
  `setDisabled`, delete and the group-membership mutations (Group events matter because
  `ROLE_ADMIN` derives from admin-group membership; `setChildren` fires no User-sourced
  `EntryUpdated`), so password changes, disables, deletions and de-elevations take effect on the
  very next request. Logout evicts explicitly (logout success handler); an epoch guard keeps a
  load that raced an invalidation from re-parking the stale identity, and the cache itself is a
  bounded Caffeine cache (maximumSize + expireAfterWrite) instead of an unbounded map. The plan's
  explicit evict beside `AuthService`'s `expireNow` was dropped as redundant: the event fires
  post-commit from the same call. Pinned by `UserDetailsCacheIT` (disable → immediate 403 on live
  session; admin password change → old password refused at once; admins-group membership replace →
  admin endpoint refused at once; requests within TTL served from cache — asserted against the
  cache of the in-JVM app, not timing).
- **D6 — proxy connection reuse.** The primary plan (migrate to a pooled `java.net.http.HttpClient`)
  was **rejected**: `SsrfValidator.openPinnedConnection` pins the connection to the DNS-resolved IP
  (rebinding guard) while preserving the original hostname for Host/SNI/cert-verification —
  `HttpClient` offers no per-request address pinning, so expressing this requires a custom
  socket/DNS layer that reintroduces the SSRF surface the design isolates. Instead
  `HttpURLConnection` stays and `ProxyService` no longer calls `disconnect()` on consumed
  responses: a fully-read body returns the connection to the JDK keep-alive pool; redirect hops are
  drained (bounded at 64 KB) before the next hop; error paths still disconnect. Additionally,
  `SsrfValidator` now memoizes one `SniSSLSocketFactory` per upstream host (bounded Caffeine cache):
  the JDK keys pooled HTTPS connections on (destination, factory instance), so the previous
  factory-per-connection made HTTPS reuse impossible. Zero SSRF-surface change: pinning, per-hop
  re-validation and the anon-whitelist re-check are untouched (`ProxyIT` 25/25).
- **Two pre-existing proxy-client bugs found by the measurement and fixed:**
  1. *Host header silently dropped.* `openPinnedConnection` sets `Host` to the original hostname
     (connections go to the pinned IP), but `HttpURLConnection` ignores the override unless
     `sun.net.http.allowRestrictedHeaders=true` — which only `BaseSpec` set, in the shared IT JVM.
     In production every proxied upstream received the raw IP as `Host`, breaking virtual-hosted
     upstreams (first symptom: Windows `http.sys` answering 400 *Invalid Hostname* for the bench
     upstream). The property is now set in `EntryStoreApplicationSpringBoot`'s static initializer.
  2. *HTTPS proxying was entirely non-functional.* The custom `HostnameVerifier` delegated to the
     default `HttpsURLConnection` verifier with the original hostname — but that verifier performs
     no certificate matching and rejects everything, so every HTTPS proxy fetch failed with
     `Wrong HTTPS hostname: should be <pinned-IP>` → 502 (fail-closed, never caught: ITs stub only
     HTTP upstreams). `SniSSLSocketFactory` now funnels all creation paths through the layered
     overload with the original hostname as TLS peer host and RFC 6125 endpoint identification
     enabled, so the handshake itself verifies the certificate against the original hostname; the
     connection-level verifier re-checks the peer-host binding and still fails closed.
- **G1 — virtual threads.** Evidence-gated load test of `spring.threads.virtual.enabled` on/off on
  the same jar (below). Default **not flipped** — no measured win at 60 concurrent clients and D9
  makes platform-pool backpressure load-bearing; property + evidence documented in
  `entrystore.properties_example`.

## How it was measured

REST-level A/B per docs 20/21: interleaved before/after fat-jar snapshots (`before` = branch tip
`9db7c7be`, `after` = + B4/D6), memory store, fresh app per round, Solr purged per round, AC power,
port-ownership assertion per round (see below). Drivers in `%TEMP%\entrystore-ab\`
(`run-b4-1090.ps1`, `run-d6-1090.ps1`, `run-g1-1090.ps1` + `bench-common-1090.ps1`).

- **B4:** 200 sequential authenticated (admin cookie) `GET /94/entry/{id}` after 15 warmup
  requests; client ms/request plus server-side `duration=` averages from
  `RequestResponseLoggingFilter` (`@Order(HIGHEST_PRECEDENCE)` — wraps the security chain, so the
  reload cost is inside the measured window).
- **D6:** 50 sequential `GET /proxy?url=http://localhost:8098/file.json` (local raw-TCP upstream,
  ~2 KB body — raw TCP because `HttpListener`/http.sys rejects the pinned connection's Host and
  the upstream counts accepted connections exactly) after 5 warmup requests; client ms/request
  plus **new upstream connections during the measured block** (the exact reuse count). Supplementary
  `run-d6b-1090.ps1`: the same for a 302 → 200 redirect chain, and 20 proxied fetches of
  `https://www.example.com/` with new-socket counting against the resolved CDN IP.
- **G1:** after-jar only, VT off vs on: 50 concurrent clients × 200 authenticated entry GETs +
  10 concurrent clients × 10 uploads of 5 MB each; per-request latencies (p50/p99/max) and peak
  JVM working set sampled at 250 ms.

**Measurement-infrastructure lesson (cost: one discarded batch).** The PATH `java` on this machine
is the Oracle `javapath` *shim*: `Start-Process java` returns the shim PID while the real JVM child
binds the port. Teardown killed only the shim, orphaned JVMs survived across rounds, a later round
whose own app failed to bind then silently measured the *previous side's* app. All drivers now
launch the real JDK `java.exe` and refuse to measure unless the port's listening PID is the process
they started (plus kill-by-port teardown).

## Results

### B4 — 200 authenticated entry GETs (client-side, ms/request)

| Round | before | after |
|---|---:|---:|
| 1 | 5.355 | 5.115 |
| 2 | 4.915 | 5.255 |
| 3 | 5.085 | 4.895 |
| **avg** | **5.118** | **5.088** |

Server-side (`RequestResponseLoggingFilter` `duration=`, mean over the 200 measured requests):
before 3.20 / 2.98 / 3.06 → **3.08 ms**; after 3.12 / 3.30 / 2.94 → **3.12 ms**. **Parity** — on a
warm memory store the per-request reload the cache eliminates was already served by core-level
entry caches (`getPrincipalEntry` soft cache, B1 memo), so its share of a ~3 ms request is below
measurement noise for a single sequential client. The cache's measurable win would need cold core
caches, large principal counts or a native store; its standing value here is architectural: the
request thread no longer runs admin-elevated PrincipalManager lookups on every request, and the
staleness contract is now explicit (5 s TTL) and eviction-tested instead of implicit.

### D6 — 50 proxied GETs of one local upstream (plain 200s, body fully consumed)

| Round | before ms/req | before new conns | after ms/req | after new conns |
|---|---:|---:|---:|---:|
| 1 | 6.06 | 0 | 6.78 | 0 |
| 2 | 7.26 | 0 | 6.92 | 0 |
| 3 | 7.00 | 0 | 7.32 | 0 |

**Both sides show zero new upstream connections** — an unexpected but instructive result: when the
response body has been read to EOF and its stream closed, the JDK has already returned the
connection to its keep-alive pool, and the subsequent `disconnect()` no longer evicts it. The
plain consumed-body GET path was therefore *already* being reused before this change (latency
parity confirms it). The change still removes the reliance on that unspecified `disconnect()`
timing — and the paths below were genuinely broken before.

#### Redirect chain — 50 proxied GETs of a local 302 → 200 hop pair

| Round | before ms/req | before new conns | after ms/req | after new conns |
|---|---:|---:|---:|---:|
| 1 | 9.12 | 50 | 6.58 | 0 |
| 2 | 8.38 | 50 | 6.14 | 0 |

Before, the unread 3xx body kept the hop connection out of the pool and `disconnect()` genuinely
tore it down — one new upstream connection per request. After, the drained hop connection is
reused for the following hop and across requests: **zero connection churn and −28 % latency** even
against a loopback upstream (the deltas grow with real network RTT).

#### HTTPS — 20 proxied GETs of `https://www.example.com/`

| | before | after |
|---|---|---|
| Round 1 | **broken — every fetch 502** | 30.3 ms/req, **0 new sockets** |
| Round 2 | **broken — every fetch 502** | 25.9 ms/req, **0 new sockets** |

Before is not a reuse comparison at all: HTTPS proxying failed outright (bug 2 above). After the
fix, all 20 internet fetches ride one pooled TLS connection — the per-host factory memoization is
what makes the JDK pool them (pool key = destination + factory instance).

### G1 — VT off vs on (after jar; 10 000 GETs @ 50 clients + 100 × 5 MB uploads @ 10 clients; 2 interleaved rounds per state)

| Metric | VT off (r1 / r2) | VT on (r1 / r2) |
|---|---:|---:|
| GET p50 (ms) | 76.8 / 77.4 | 88.2 / 81.7 |
| GET p99 (ms) | 392.7 / 341.8 | 411.2 / 368.4 |
| GET max (ms) | 532.7 / 541.6 | 785.2 / 524.3 |
| Upload p50 (ms) | 1383.9 / 1164.0 | 1405.5 / 1324.1 |
| Upload p99 (ms) | 2345.3 / 1816.1 | 2263.5 / 1898.2 |
| Peak RSS (MB) | 624 / 662 | 572 / 598 |
| Total wall (s) | 20.4 / 20.3 | 22.8 / 21.8 |

**Decision: the default stays `spring.threads.virtual.enabled=false` (not flipped).** At 60
concurrent clients virtual threads bought nothing: GET p50 was 6–14 % *slower*, total wall-clock
7–12 % longer, tail latencies equal-or-worse; the only VT-favourable signal was a ~8 % lower peak
RSS — not enough to offset the latency regression, and D9 (uploads buffer fully in memory) means VT
would remove the platform-thread-pool backpressure that today caps concurrent upload buffers. Per
the ticket's own conditional wording this evidence-documented no-flip resolves G1; the property is
documented in `entrystore.properties_example` for deployments with very high connection counts,
where the calculus may differ.

## Implication

- **B4** is latency-neutral on a warm memory store (the reload it removes was already cheap), but
  every authenticated request now avoids an admin-elevated PrincipalManager round-trip, and the
  identity-staleness contract went from implicit to explicit-and-tested: password change, disable,
  delete, group-membership de-elevation and logout all take effect on the next request via
  synchronous event eviction, with the 5 s TTL only as a safety net.
- **D6**'s value concentrated where the connection actually died: redirect chains (one fewer TCP
  connection per request, −28 % latency at loopback RTT) and HTTPS — which turned out to be
  entirely broken, not merely unpooled. The two fixed bugs mean proxied upstreams now receive the
  correct `Host` header and HTTPS upstreams are reachable at all, with full certificate
  verification against the original hostname and TLS connections amortized across requests.
- **G1**: virtual threads stay off by default — measured 6–14 % slower GET p50 and 7–12 % longer
  wall-clock at 60 concurrent clients with no tail-latency win, and D9 (in-memory upload
  buffering) makes the platform pool's backpressure a feature. The property and the evidence are
  documented for high-connection-count deployments.

## How to reproduce

```powershell
# %TEMP%\entrystore-ab\ — jars in app-1090\{before,after}.jar, Solr 10 Docker on 8983
.\run-b4-1090.ps1 -Side before -Tag b1   # interleave sides, 3+ rounds each
.\run-d6-1090.ps1 -Side after  -Tag a1
.\run-g1-1090.ps1 -Vt true     -Tag on1
# unit/IT gates: UserDetailsCacheIT, CookieLoginResourceIT, LogoutIT, ProxyIT
```
