# 03 — Conversion Runner

**Depends on:** 02, uses types from 05 & 06. **Decisions:** A4, B5, C1, D4, D5, D9.

## Purpose

Convert **one** `.mrxs` to **one** `.ome.tiff`, headless. No UI, no batching, no discovery.

## Interface (sketch)

```java
ConversionResult convert(ConversionJob job, ConversionSettings s, ToolPaths tools,
                         ProgressListener p, CancellationToken cancel);
// ConversionJob: input .mrxs path, final output path, log file path
// ConversionResult: SUCCEEDED | FAILED(reason) | CANCELLED, duration, logPath
```

## Behavior

1. Create temp working dir via 05 (`TempWorkspace`) under system temp.
2. Step 1 — child JVM:
   `<qupath java.exe> -Xmx<heap> --add-opens java.base/java.lang=ALL-UNNAMED -Djava.io.tmpdir=<workspace> -Dlogback.configurationFile=<b2r config> -cp <classpathB2R> com.glencoesoftware.bioformats2raw.Converter <input.mrxs> <workspace>\pyramid [settings args]`
3. Step 2 — child JVM:
   `<java.exe> -Xmx<heap> -Djava.io.tmpdir=<workspace> ... -cp <classpathR2O> com.glencoesoftware.pyramid.PyramidFromDirectoryWriter <workspace>\pyramid <output>.ome.tiff.partial [settings args]`
4. On success: atomic rename `.partial` → `.ome.tiff`. If rename target appeared meanwhile, fail (don't overwrite — B4).
5. **Always** (finally): delete workspace; delete `.partial` if present.
6. stdout + stderr of both steps appended to the job's log file, with command lines and exit codes.
7. Progress: coarse phases (`EXTRACTING` 0–50%, `WRITING` 50–100%); parse tool output for finer progress only if cheap.

## Cancellation (D4)

- Token checked between steps and polled while a child runs.
- On cancel: kill the child **process tree** (`ProcessHandle.descendants()` then the process), wait for exit, then cleanup (step 5). Result `CANCELLED`.

## Settings → CLI args

Only emit an arg when the user changed it from the CLI default (D1). Mapping table lives in 06.

## Out of scope

Choosing output path, skip-if-exists, disk-space check, concurrency (all 04/07).

## Acceptance

- Sample fluorescence `.mrxs` → output passes 09 comparison.
- Non-zero exit in step 1 → `FAILED`, no `.partial`, no workspace left, log contains stderr.
- Cancel mid-step-1 and mid-step-2 → no java child processes remain, no files left behind.
- Killing QuPath mid-run leaves a workspace that 05's startup cleanup removes.
