# 07 — Batch Queue

**Depends on:** 03, 04, 05, 06. **Decisions:** B4, B6, B7, C2, D3, D4.
**Status:** Done (2026-10-07). Code: `batch/BatchQueue` (`start(plan, settings, listener)` → `BatchHandle`),
`DiskSpaceGuard` (rule 3 constants live here), `ZarrSize`, `JobState`, `BatchReport`. Tests: `BatchQueueTest`,
`DiskSpaceGuardTest`, `ZarrSizeTest` (fakes), `BatchQueueIntegrationTest` (real tools on `reference/`).
Details settled in code: the runner gained a `StepGate` hook, called between step 1 and step 2, for the uncompressed
re-check. That re-check counts whole tiles (an upper bound: APOL1 1.43 GB vs 1.37 GB measured), and if the Zarr can't be
read, the 80× pre-flight stands. Every failed job has a log, so spec 08's *Show log* always works. When the runner never ran (invalid slide, pre-flight
refusal with its figures, tools not extracted, unexpected error), `FailureLog` writes a short one. Logs are
`%LOCALAPPDATA%\mantis-mrxsconvert\logs\<batch time>_<NN>_<slide>.log`. Queued jobs become CANCELLED as soon as
Cancel is pressed. `BatchQueue.heapWarning(...)` is public so the UI can show rule 8 before Start.

## Purpose

Run a `Plan` through the runner with bounded concurrency, guardrails, cancellation, and a final report. No UI.

## Interface (sketch)

```java
BatchHandle start(Plan plan, ConversionSettings s);
// BatchHandle: observable per-job state + overall progress, cancel(), CompletableFuture<BatchReport>
// JobState: QUEUED | RUNNING(phase,%) | SUCCEEDED | SKIPPED | FAILED(reason, log) | CANCELLED
```

## Rules

1. Only `READY` jobs run; `SKIP_EXISTS` → `SKIPPED`, `INVALID` → `FAILED` immediately (reason from 04).
2. Executor with `parallelFiles` workers (default 1). Background threads only; never block the FX thread.
3. **Pre-flight (D3), per job, just before it starts.** `input` = size of `.mrxs` + its data folder.
   - Temp volume needs `TEMP_FACTOR × input` (Zarr); output volume needs `OUTPUT_FACTOR × input` (TIFF).
     Same volume → the sum (Zarr and TIFF coexist during step 2).
   - Each volume must also keep `HEADROOM` free after the reservation.
   - Subtract space already reserved by running jobs before comparing.
   - Insufficient → `FAILED("Not enough disk space: need X, have Y on <drive>")`.
   - Constants live in one place:

     | Constant | Value | Basis |
     |----------|-------|-------|
     | `TEMP_FACTOR` | 7.5 | max observed Zarr 5.89× input, +~25% margin |
     | `OUTPUT_FACTOR` | 5.5 | max observed TIFF 4.41× input, +~25% margin |
     | `HEADROOM` | 1 GiB | never fill a drive to zero |

     Calibrated on `reference/` with default settings (LZW): PROX1 110 MB → Zarr 650 MB + TIFF 487 MB (10.3×);
     APOL1 69 MB → Zarr 302 MB + TIFF 201 MB (7.3×). Margin also covers NTFS cluster overhead of ~8k Zarr chunk files.
     Non-default compression (spike 01 Q10, ratios TIFF/input):

     | Compression | Measured (PROX1 / APOL1) | Rule |
     |-------------|--------------------------|------|
     | LZW | 4.41× / 2.93× | `OUTPUT_FACTOR` 5.5 (above) |
     | JPEG | 1.47× / 0.56× | `OUTPUT_FACTOR_JPEG` 2.0 |
     | Uncompressed | 76.1× / 19.8× | **not a factor** — sparse slides make any factor wrong. Pre-flight uses 80× as a cheap first gate, then **after step 1** compute the exact need from the Zarr `.zarray` shapes (Σ levels W·H·C·bytes per series, incl. label/macro) and re-check the output volume before step 2; insufficient → `FAILED` as above. |

     Temp (Zarr) factor is unaffected by output compression. bioformats2raw 0.12.1 Zarr measured 623 MB / 300 MB
     (slightly below the reference dirs, which also hold empty chunks).
4. **Re-check skip** right before starting (output may have appeared since planning).
5. Failure of one job never affects others (B4).
6. **Cancel (D4):** stop dequeuing; cancel running jobs via token; remaining → `CANCELLED`; completed outputs kept.
7. **Report:** counts + per-job outcome, reason, log path, duration.
8. Heap guard: if `parallelFiles × heapPerFile` > ~75% physical RAM, warn (in report/UI), don't block.
   With heapPerFile = Auto (E3) count each JVM as 25 % of RAM (the JVM default max), so the warning starts at parallelFiles = 4.
   (Spike 01: real use is far lower — ~0.5–1.5 GB working set per JVM on the samples — so this is a ceiling, not a forecast.)

## Out of scope

Rendering progress (08). Deciding outputs (04).

## Acceptance (runner can be faked)

- 5 jobs, parallel=1 → strictly sequential; parallel=2 → never >2 running.
- Job 2 fails → jobs 3–5 still run; report shows 1 failed.
- Low-space fake → that job FAILED with readable message; others continue.
- Space math: 100 MB input, separate volumes → needs 750 MB + 1 GiB on temp, 550 MB + 1 GiB on output;
  same volume → 1300 MB + 1 GiB. Two running jobs' reservations are both subtracted.
- Cancel during job 2 of 5 → job 1 SUCCEEDED, 2 CANCELLED, 3–5 CANCELLED, no running children.
