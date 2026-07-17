# Finding 27: List remove/renumber and batch-honouring setters (ENTRYSTORE-1089)

Date: 2026-07-17
Branch: `feature/benchmark-ai-deferred`
Ticket: [ENTRYSTORE-1089](https://metasolutions.atlassian.net/browse/ENTRYSTORE-1089)

## What was changed

- **C2 — targeted single-child removal.** `ListImpl.removeChild` no longer clears and rewrites
  the whole `rdf:Seq` graph; it removes the child's statement and renumbers only the tail
  (`rdf:_(i+1)` → `rdf:_i`). **With a crossover heuristic**: measurement showed per-position
  remove+add pairs cost ~3× a bulk-rewrite add, so an *unconditional* renumber made
  front-of-list removals 2× slower — long tails (> ⅓ of the list) therefore fall back to the
  full `saveChildren` rewrite. `setChildren` and the move operations keep the full rewrite.
  The ordering invariant (security-relevant: `GroupImpl` membership is list order) is pinned by
  `ListRemoveRenumberTest`: removal at every position, interleaved removals, emptied-then-refilled
  lists and group membership all verified against a fresh load from the committed RDF.
- **C3 — one-transaction ACL copy.** New `EntryImpl.updateAllowedPrincipals(Map<AccessProperty,
  Set<URI>>, replace, append)` applies all access properties in a single transaction;
  `ContextImpl.copyACL` (the create-in-list path, also used by the four REST create call sites)
  now pays **1 transaction instead of 5** per created entry (the ACL path never wrote
  modified dates nor fired repository events, before or after this change).
  `updateAllowedPrincipalsFor` delegates to it, so single-property semantics are unchanged.
- **C12 — batch-aware setters.** `updateAllowedPrincipals` and the `replaceStatement` family
  (setStatus, setFilename, setFileSize, setMimetype) now join an active
  `RepositoryManagerImpl.inBatch` transaction (the `doSetGraph` pattern) instead of committing
  individually inside it. `BatchAwareSettersTest` pins identical end state inside and outside a
  batch, asserted against fresh loads from committed RDF. The ENTRYSTORE-1085 post-commit group
  cache clear in `inBatch` is load-bearing for these now (events fire pre-commit inside batches).
- **API.** `RepositoryManager.inBatch(Runnable)` is now on the core-api interface, so REST bulk
  imports no longer need to cast to `RepositoryManagerImpl` (doc 03 follow-up).

## How it was measured

New harness mode **`-L/--list-benchmark <N>`** (benchmark-entrystore): fills one list with N
committed children, then times 250 one-by-one removals **from the end** (the bulk-deletion
shape — minimal tail) and 250 **from the front** (maximal tail — the parity check for the
heuristic). Interleaved A/B jar snapshots (before = branch tip without C2, identical harness),
native store, fresh store dir per run, N=1000.

## Results (250 removals per phase, 1 000-child list, ms)

| Phase | before | after | Δ |
|---|---:|---:|---:|
| Remove from end | 4 932 / 5 111 → **5 022** | 815 / 956 / 1 002 → **924** | **−81.6% (5.4×)** |
| Remove from front | 3 010 / 3 329 → 3 170 | 3 251 / 3 325 / 3 508 → 3 361 | parity (heuristic fallback) |
| Fill (250×4 addChild) | ~3.4–4.5 s | ~3.6–4.5 s | flat |

An earlier measurement round drove the design: the unconditional renumber scored front
removals at **6 784 ms vs 3 398 ms before (+100%)** — the tail-length crossover (≤ ⅓ of the
list) keeps the targeted path where it wins and the old path where it doesn't. A couple of
rounds taken under ambient system load showed proportionally inflated numbers on *both* sides;
the clean interleaved rounds above are canary-consistent.

## Implication

- **Bulk deletions through a shared list** (the C2 motivation: deleting N entries removes each
  from its referring list) now cost O(1) per removal instead of O(list size) when removals hit
  the latter part of the list — the common append-then-prune lifecycle. Front-heavy removal
  patterns are unchanged by design.
- The create-in-list REST path saves 4 transactions per created entry (C3); `inBatch`
  callers now get true single-commit semantics for ACL updates,
  status and file-metadata setters (C12), which is what production bulk-import wiring via the
  new `RepositoryManager.inBatch` interface method needs.

## How to reproduce

```powershell
java -Xmx2g -jar modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar `
  -s native -u 10 -L 1000 -p <fresh-dir>
# compare "Removing 250 children from the end/front took" across jar snapshots
# unit gates: ListRemoveRenumberTest, BatchAwareSettersTest, ListImplTest, GroupImplTest
# IT gates: ResourceIT, EntryIT, LocalEntryIT
```
