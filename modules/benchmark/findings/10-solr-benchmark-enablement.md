# EntryStore Benchmark — Solr Benchmark Enablement + Fresh Baselines

Date: 2026-07-10
Module: `modules/benchmark/{benchmark-common,benchmark-solr}`
Branch: `feature/benchmark-ai`

Prerequisite work for the doc-08 ("Fable investigation") implementation
round: docs 11–13 measure the Solr indexing pipeline (findings A1–A16),
which requires a runnable `benchmark-solr` — and it could not run at all.

## What was broken

`benchmark-solr` set `entrystore.solr.url` to a **filesystem temp path**
(`Arguments.setSolrPath()`), a leftover from the embedded-Solr era.
`RepositoryManagerImpl` nowadays requires an `http(s)` URL and throws
`IllegalStateException("Embedded Solr is no longer supported…")` for
anything else, so the module crashed at startup.

A second, unrelated CLI bug was found while in there: the `-a/--acl`
option parsed **the `-t` option's value** (copy-paste error), so any
explicit `-a` value disabled ACL mode regardless of what was passed.

## What was added

- New `-S`/`--solr-url` flag (benchmark-common `BenchmarkCommons` +
  `Arguments`); `benchmark-solr` fails fast with a clear message when the
  flag is missing or not `http(s)`.
- `-a/--acl` now reads its own option with intuitive boolean semantics
  (`-a false` turns ACL mode off; absent = on). The documented `-t`
  inversion from findings doc 02 is untouched.

## How to run Solr for the benchmark (Docker)

Mirrors the integration tests (`BaseSpec`): same image, same core config.

```powershell
docker run -d --name bench-solr -p 8983:8983 -e SOLR_MODULES=analysis-extras solr:10.0.0
docker exec -u root bench-solr mkdir -p /entrystore-core/conf
docker cp modules\rest\integration-test\src\test\resources\solr\. bench-solr:/entrystore-core/conf
docker exec -u root bench-solr chown -R solr:solr /entrystore-core
docker exec bench-solr solr create -c entrystore-core -d /entrystore-core

# purge the core between benchmark runs:
curl.exe -s -X POST "http://localhost:8983/solr/entrystore-core/update?commit=true" `
  -H "Content-Type: text/xml" --data "<delete><query>*:*</query></delete>"
```

The benchmark polls `SolrSearchIndex.getPostQueueSize()` after the insert
loop, so the reported "Adding to context and sending data to Solr took"
figure includes draining the async indexing queue.

## Fresh baselines (HEAD `1991a688`, 2026-07-10)

All numbers from this machine state (Windows 11 / NTFS, `-Xmx2g`,
Java 25); fresh store dir per run. These are the "before" columns for
findings docs 09 and 11–14. Note: absolute numbers are **not comparable
to docs 01–07** — the branch has since been rebased onto develop-spring
(different core code) and the machine state differs.

### `NB` — native batched, 10 000 simple persons (`-s native -u 10000 -m 2000 -B true`)

| Run | Insert | Read |
|---|---:|---:|
| 1 | 23 103 | 2 035 |
| 2 | 25 307 | 1 884 |
| 3 | 21 199 | 2 098 |
| **avg** | **23 203** | **2 006** |

### `NU` — native unbatched, 2 000 simple persons (`-s native -u 2000 -m 500`)

| Run | Insert | Read |
|---|---:|---:|
| 1 | 44 141 | 388 |
| 2 | 39 963 | 381 |
| **avg** | **42 052** | **385** |

### `MU` — memory unbatched, 10 000 simple persons (`-s memory -u 10000 -m 2000`)

| Run | Insert | Read |
|---|---:|---:|
| 1 | 6 509 | 1 024 |
| 2 | 7 502 | 954 |
| **avg** | **7 006** | **989** |

### `SOLR` — native unbatched + Solr indexing, 2 000 simple persons
(`benchmark-solr -s native -u 2000 -m 500 -S http://localhost:8983/solr/entrystore-core`)

| Run | Insert | Insert + queue drain | Read |
|---|---:|---:|---:|
| 1 | 51 411 | 52 413 | 503 |
| 2 | 53 061 | 54 063 | 386 |
| 3 | 47 426 | 48 428 | 370 |
| **avg** | **50 633** | **51 635** | **420** |

## First observation (motivates docs 11–12)

Same workload with Solr **off** (`NU`) averages 42 052 ms; with Solr
**on**, 50 633 ms — the synchronous `SolrInputDocument` construction on
the writer thread (finding A1: built under `synchronized(repository)` →
`synchronized(repositoryListeners)` → `synchronized(postQueue)`; finding
A2: ~8–10 metadata-graph loads per document) adds **~20% to every
insert** before the async submitter even sees the document. The queue
drain itself adds only ~1 s at this scale — the cost sits inline in the
write path, exactly as doc 08 predicted.

## How to reproduce

```powershell
$jar = "modules\benchmark\benchmark-solr\target\entrystore-benchmark-solr-6.0-SNAPSHOT.jar"
java -Xmx2g -jar $jar -s native -u 2000 -m 500 `
    -S http://localhost:8983/solr/entrystore-core `
    -p "$env:TEMP\entrystore-bench\solr-run"
```
