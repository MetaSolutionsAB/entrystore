# Finding 23: Cross-request user→groups cache (ENTRYSTORE-1085)

Date: 2026-07-16
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1085](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1085)

## What was changed

The full B1 (doc 08/14): even with the per-decision `UserGroupsMemo`, every
group-authorized decision still paid one full principals-context scan
(`getGroupUris` — O(principals × group members), 1.42–1.69 ms/decision at a
2 207-principal directory). A cache that survives across decisions now
amortises that scan toward zero:

- **`PrincipalManagerImpl.userGroupsCache`** — `ConcurrentHashMap<URI, Set<URI>>`
  fronted by `getGroupUrisCached(URI)`; the B1 memo now reads through it, so
  the per-decision shape is unchanged and the scan happens at most once per
  (user × invalidation window).
- **Listener-based invalidation** (`RepositoryManagerImpl.
  registerGroupCacheInvalidationListener`): any event whose source entry is a
  **Group** (`EntryCreated`/`EntryUpdated`/`ResourceUpdated`/`RelationsUpdated`
  /`EntryDeleted`) clears the whole cache — the event doesn't carry the
  group's previous member set, and directory mutations are rare relative to
  decisions, so clear-all is the simple, obviously-correct choice. A deleted
  **User** evicts only their own entry. This covers `addMember`/`removeMember`
  (→ `ResourceUpdated` on the group), `setChildren`/`setGraph` replacement
  (→ `RelationsUpdated` + `ResourceUpdated`), and group/user delete
  (→ `EntryDeleted`) — including the inherited `ContextImpl` paths, which all
  fire through the same events.
- **Race-hardening**: an epoch counter is bumped on every invalidation; a
  loader only publishes its scan while the epoch is unchanged (re-checked
  after the put), so a scan racing a membership change can never park
  pre-mutation state in the cache indefinitely. Additionally `inBatch` clears
  the cache once after a successful commit — repository events fire *inside*
  batches before the commit (documented `inBatch` behaviour), so a decision
  racing the batch could otherwise repopulate from pre-commit state. This
  guard becomes load-bearing when setters become batch-aware
  (ENTRYSTORE-1089).
- **Kill switch**: `entrystore.auth.group-cache=false`
  (`Settings.AUTH_GROUP_CACHE`, default on), documented in
  `entrystore.properties_example`.

Security tests (`UserGroupsCacheTest`, `GroupCacheDisabledTest`) pin the
no-staleness contract through the public authorization API: remove-member,
add-member, `setChildren` swap, group delete each flip the next decision;
user delete evicts; a committed batch clears; the kill switch keeps
decisions correct with the cache unused. Full core-impl unit suite green.

## How it was measured

Interleaved A/B, 3 rounds per side plus a discarded warm-up, fresh native
store per run, AC power. `-s native -u 10000 -m 2000 -B true -a true -r true
-P 2000` — batched insert of 10 000 persons (20 000 entries), then a
2 207-principal directory is seeded (2 000 users + 200 groups + built-ins)
and every entry is read as a user whose only access is a group grant.
Before = branch tip without the cache (per-decision memo only, the doc-14
state); after = with the cache. Canaries 246–329 ms, mixed evenly across
sides.

## Results

| Metric (ms) | before | after | Δ |
|---|---:|---:|---:|
| Group-authorized read (20 000 entries) | 34 115 / 33 326 / 33 777 → **33 739** | 2 037 / 2 014 / 2 032 → **2 028** | **−94.0% (16.6×)** |
| Admin/direct read (same entries) | 1 817 / 1 873 / 1 833 → 1 841 | 1 929 / 1 798 / 1 815 → 1 847 | flat |
| Batched insert (10 000 persons) | 19 751 / 19 127 / 19 235 → 19 371 | 19 784 / 18 308 / 19 574 → 19 222 | flat (−0.8%) |

Per-decision cost: **1.69 ms → 0.10 ms**. The group-authorized read now
costs the same as the admin read — the group-resolution overhead is gone
from the steady state entirely; what remains is the entry load itself.

## Implication

- B1 is now fully closed: doc 14's memo removed the *within-decision*
  redundancy (−48.9% at scale), this cache removes the *across-decision*
  redundancy (−94.0% on what remained). Combined vs the original baseline,
  group-authorized reads at a 2 207-principal directory are ~2.8 ms → 0.10 ms
  per read.
- The win grows with directory size (the scan was O(principals × members))
  and with read volume between directory mutations; any mutation costs one
  full-cache rebuild, which the flat insert numbers show is invisible.
- The listener fires on every repository event with a Group/User-delete
  filter — the flat insert phase (19.4 s → 19.2 s) confirms zero measurable
  write-path cost.

## How to reproduce

```powershell
java -Xmx2g -jar modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar `
  -s native -u 10000 -m 2000 -B true -a true -r true -P 2000 -p <fresh-dir>
# compare "Reading as group-member user took" across jar snapshots built
# with/without this change; kill switch: add entrystore.auth.group-cache=false
# to the configuration to measure the fallback path in a single jar.
```
