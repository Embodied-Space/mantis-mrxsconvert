# 03 — Conversion Runner

**Depends on:** 02, uses types from 05 & 06. **Decisions:** A4, B5, C1, D4, D5, D9.
**Status:** Done (2026-10-07). Code: `runner/ConversionRunner`. Tests: `ConversionRunnerTest` (command lines, pre-checks),
`ConversionRunnerIntegrationTest` (real payload + `reference/` samples; skipped without them).

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

0. Fail fast, before any work, if the output already exists. Create the output's parent folder (mirrored trees, E1).
   Then claim the output (D5): exclusive lock on hidden `<output>.partial.lock` (`OutputLock`), held until the end.
   Lock held elsewhere → `FAILED` ("another conversion is already writing it"), nothing touched. Lock acquired and a
   `.partial` exists → it is a crash leftover: delete it (log it), then continue. Works across machines on a share.
1. Create temp working dir via 05 (`TempWorkspace`) under system temp.
2. Step 1 — child JVM:
   `<ToolPaths.javaExe> [-Xmx<heap>] --add-opens java.base/java.lang=ALL-UNNAMED --enable-native-access=ALL-UNNAMED -Djava.io.tmpdir=<workspace> -Dlogback.configurationFile=<b2r config> -cp <classpathB2R> com.glencoesoftware.bioformats2raw.Converter <input.mrxs> <workspace>\pyramid [settings args]`
3. Step 2 — child JVM:
   `<ToolPaths.javaExe> [-Xmx<heap>] --enable-native-access=ALL-UNNAMED -Djava.io.tmpdir=<workspace> ... -cp <classpathR2O> com.glencoesoftware.pyramid.PyramidFromDirectoryWriter <workspace>\pyramid <output>.ome.tiff.partial [settings args]`
   - `java.exe` is the payload's bundled JRE (02, A4 revised), never QuPath's runtime (it has none) or one on `PATH`.
   - `-Xmx` only when heapPerFile ≠ default (E3: JVM default). `--enable-native-access` only silences Java 25 warnings;
     it's a JVM flag, not a tool arg, so D1 holds. Both steps also get `-Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8`
     (JVM flags) so non-ASCII paths reach the log intact; the log is UTF-8.
   - Child environment: **remove** `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`, `JAVA_HOME`, `CLASSPATH`
     so a user's machine settings can't leak in (spike 01: `JAVA_TOOL_OPTIONS` is honoured).
4. On success: rename `.partial` → `.ome.tiff`. If rename target appeared meanwhile, fail (don't overwrite — B4).
   Use a plain `Files.move`, **not** `ATOMIC_MOVE`: on Windows that replaces an existing target.
5. **Always** (finally): delete workspace; delete `.partial` (still under the lock, so it is this run's); release and delete the lock file.
6. stdout + stderr of both steps appended to the job's log file, with command lines and exit codes.
7. Progress: coarse phases (`EXTRACTING` 0–50%, `WRITING` 50–100%); parse tool output for finer progress only if cheap.

## Cancellation (D4)

- Token checked between steps and polled while a child runs.
- On cancel: kill the child **process tree** (`ProcessHandle.descendants()` then the process), wait for exit, then cleanup (step 5). Result `CANCELLED`.
- Interrupting the converting thread counts as cancel; cleanup still runs (the log uses `java.io`, not an NIO channel,
  because NIO channels close on interrupt).

## Settings → CLI args

Only emit an arg when the user changed it from the CLI default (D1). Mapping table lives in 06.

## Out of scope

Choosing output path, skip-if-exists, disk-space check, concurrency (all 04/07).

## Acceptance

- Sample fluorescence `.mrxs` → output passes 09 comparison.
- Non-zero exit in step 1 → `FAILED`, no `.partial`, no workspace left, log contains stderr.
  (Spike 01: too little heap can crash the JVM natively — `EXCEPTION_ACCESS_VIOLATION`, `hs_err_pid*.log` — instead of an OOM;
  any non-zero exit is a failure. Keep `hs_err` files out of the output folder: the child's working dir is the workspace.)
- Cancel mid-step-1 and mid-step-2 → no java child processes remain, no files left behind.
- Killing QuPath mid-run leaves a workspace that 05's startup cleanup removes.
