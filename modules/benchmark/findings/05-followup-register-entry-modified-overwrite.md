# EntryStore Benchmark — `registerEntryModified` Overwrite-in-Place

Date: 2026-06-09
Module: `core/core-impl`
Branch: `feature/benchmark-ai`

Follow-up to `2026-06-08-skip-remove-empty-metadata.md`. The Rec 2
findings flagged `EntryImpl.registerEntryModified` as the next-largest
piece of avoidable work in the per-insert path: every metadata write
triggered a `getStatements + pattern-remove` cycle to clear the prior
modified-date triple, even for the very first call (when no prior
triple exists yet).

## What changed

`core/core-impl/.../EntryImpl.java` — `registerEntryModified`:

- Captures `previousModified = this.modified` before reassigning.
- On the very first call (no previous value tracked): skip the remove
  entirely. The entry context contains no Modified triple at that
  point (only Created has been added in `initialize`).
- On every subsequent call: exact-remove
  `(entryURI, Modified, previousModified, entryURI)` instead of a
  pattern remove over `(entryURI, Modified, null)` — same semantic
  result for the single-writer scenario (where we wrote the previous
  value ourselves or loaded it from the repo), without the
  `getStatements` scan.
- The `contributors` logic and the new `rc.add(...)` are unchanged.

`setGraphRaw` is intentionally not touched: it has no live callers
outside of `EntryImpl` itself, so its corner case (metametadata
parameter containing a `Modified` triple with a value that differs
from the stale `this.modified`) is purely theoretical.

## Results (3 fresh runs each, same-session apples-to-apples)

Same-session protocol: run 3× with current code → `git stash` → rebuild
→ run 3× baseline → `git stash pop` → rebuild.

### 10 000 persons

| Mode | Baseline avg | With change avg | Δ |
|---|---:|---:|---|
| memory unbatched | 12 799 ms | 12 126 ms | ~5% faster |
| memory batched | 7 666 ms | 7 266 ms | ~5% faster |
| native batched | 31 423 ms | 29 143 ms | ~7% faster |

Memory and native batched runs (full numbers):

| Mode | Baseline (3 runs) | With change (3 runs) |
|---|---|---|
| memory unbatched | 12232, 13042, 13123 | 12190, 12315, 11872 |
| memory batched | 7489, 7907, 7602 | 7350, 6976, 7473 |
| native batched | 30159, 30806, 33303 | 28980, 29148, 29300 |

### 5 000 persons — native unbatched (the long-running case)

| Run | Baseline | With change |
|---|---:|---:|
| 1 | 148 703 ms | 159 613 ms |
| 2 | 152 888 ms | 147 305 ms |
| 3 | 143 606 ms | 148 307 ms |
| **avg** | **148 399 ms** | **151 742 ms** |

**Statistically indistinguishable.** The 3.3 s mean difference is
within the per-mode standard deviation (~5–6 s), and run 1 in each
direction actually trends the wrong way. Native unbatched is dominated
by per-commit overhead (4 commits × 5 000 persons = 20 000 commits at
~7.5 ms each = ~150 s); the ~50 µs savings per `registerEntryModified`
call × 20 000 ≈ 1 s of improvement is lost in commit-level noise.

The change is effectively **neutral** on native unbatched — neither a
regression nor a measurable win. The improvement only shows up where
commit overhead has already been amortised away (native batched) or
was never the bottleneck (memory).

## Why the savings are small

`registerEntryModified` was already cheap in absolute terms: the
`getStatements` it ran was scoped to the entry's own context
(`entryURI`) which only ever holds ~10 triples (the entry metadata
itself — created, modified, resource URI, metadata URI, ACL triples,
etc.). On NativeStore the `cspo` index lookup is `O(log N)` over a
~10-element subtree — a few microseconds per call.

The fix saves ~50 µs per `createResource` × 2 `createResource`s per
person × 10 000 persons ≈ 1 s on native, which is the order of
magnitude we observed (avg 31.4 → 29.1 s, Δ ≈ 2.3 s — close enough
given the system variance).

This is the law of diminishing returns: the cheap-to-find, low-impact
optimisation. Larger wins now require attacking the items that scale
with N, not the items that run once per entry.

## Caveat about system variance

While preparing this measurement, observed wall-clock for native
unbatched on the same code varied from 256 s to 372 s on consecutive
runs — a 1.5× spread purely from background system load /
filesystem state. Wall-clock comparisons across separate measurement
sessions are unreliable for native; only same-session apples-to-apples
(stash → run → un-stash → run) is trustworthy.

Per-insert sampled times (Peter Griffin modulo=500 on native batched)
are flat at 0–3 ms throughout the run, confirming that no per-op
regression is present even when the wall-clock looks worse.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-entrystore\target\entrystore-benchmark-entrystore-6.0-SNAPSHOT.jar"

# 3 same-session runs at 10 000 persons, native batched:
for ($i=1; $i -le 3; $i++) {
  $dir = "$env:TEMP\entrystore-bench\run-$i-$([guid]::NewGuid())"
  java -Xmx2g -jar $jar -s native -u 10000 -m 2000 -B true -p $dir
}
```

## What's left from the recommendation list

- #3 — `forceSync=false` on NativeStore for bulk-import mode (opt-in,
  data-loss trade-off). Configuration knob.
- #4 — re-evaluate `cspo,spoc` indexes; `cspo` alone may halve write
  amplification. Configuration knob.
