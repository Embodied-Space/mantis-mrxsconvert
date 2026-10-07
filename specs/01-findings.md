# 01 — Spike Findings: QuPath 0.7 Runtime

**Date:** 2026-10-07. **Machine:** Windows 11, 24 logical CPUs, 31 GB RAM. **QuPath:** 0.7.0 at
`%LOCALAPPDATA%\QuPath-0.7.0` (per-user install). All probes were throwaway scripts; none are product code.

## Verdict

**Go on QuPath 0.7**, but **A4 did not hold as written**: QuPath 0.7's runtime has **no `java.exe`**.
The user chose to bundle our own JRE (A4 revised; see "Decisions taken").

## Answers

### Q1 — Bundled Java
Java **25.0.2** (`runtime\release`). jlink'd image, modules: `java.se`, `jdk.management.agent`, `jdk.unsupported`,
`jdk.zipfs` (+ their deps). Both tools need ≥ 11 → OK. Both tools run on it with no missing-module errors.

### Q2 — Extension API
`qupath.lib.gui.extensions.QuPathExtension` (read by reflection in 0.7.0):
- abstract `String getName()`, `String getDescription()`, `void installExtension(QuPathGUI)`
- default `Version getVersion()`, `Version getQuPathVersion()`, `void installHeadless()`

Extensions are loaded by `extensionmanager-1.1.1` (`qupath.ext.extensionmanager.core.ExtensionClassLoader`,
a `URLClassLoader` with `addJar(Path)`). Default user dir: `%USERPROFILE%\QuPath\v0.7`.
**Gradle (resolved 2026-10-07):** `qupath-extension-template` @ `bd825fc` targets QuPath 0.7.0 with settings plugin
`io.github.qupath.qupath-extension-settings` 0.2.1 and Gradle 9.4.0. Built with JDK 25.0.4.1 (Oracle): it resolves
`qupath-core`/`qupath-gui-fx` 0.7.0, emits class version 69 (QuPath 0.7's own jars are 69), and registers through
`META-INF/services/qupath.lib.gui.extensions.QuPathExtension`. Pinned details are in spec 10.

### Q3 — Locating the runtime / launcher
- `System.getProperty("java.home")` → `…\QuPath-0.7.0\runtime`.
- `System.getProperty("jpackage.app-path")` → the running QuPath launcher `.exe` (GUI or console variant).
- **`runtime\bin` contains no `java.exe` / `javaw.exe`** (jlink `--strip-native-commands`). Only DLLs
  (`jli.dll`, `server\jvm.dll`, …). The install contains exactly two `.exe` files: `QuPath-0.7.0.exe` and
  `QuPath-0.7.0 (console).exe`. Both are jpackage launchers.

**Workaround (proven):** a jpackage launcher reads `<its dir>\app\<its name>.cfg`. That file supports
`app.runtime=<absolute path>`, `app.mainclass=`, `app.classpath=` (repeatable; `dir\*` wildcards work) and
`[JavaOptions] java-options=` (one token per line). So:
1. Copy QuPath's launcher (`jpackage.app-path`, ~580 KB) to e.g. `<dir>\b2r.exe`.
2. Write `<dir>\app\b2r.cfg` pointing `app.runtime` at `java.home`, with the tool's classpath and main class.
3. Run `<dir>\b2r.exe <tool args…>`. Args pass through unchanged.

Verified properties:
- Both the console and the GUI-subsystem launcher work; stdout/stderr are captured when redirected.
- The cfg must be **UTF-8 without BOM** (with a BOM, the file is ignored → "Failed to find JVM in <dir>\runtime").
- Paths with spaces **and non-ASCII characters** work in `app.runtime`, `app.classpath` and `java-options`.
- **The launcher re-launches itself:** the JVM runs in a *child* process of the launcher. That child sits in a
  kill-on-close job object: killing the launcher killed the JVM child within 2 s. Spec 03 still kills the whole tree
  (`descendants()` first) to be safe.
- `JAVA_TOOL_OPTIONS` is honoured, and an inherited value would leak into the children ("Picked up JAVA_TOOL_OPTIONS"). The runner
  must **remove `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, `JDK_JAVA_OPTIONS`** from the child environment, and pass
  all JVM options through the cfg.
- Exit codes propagate (0 on success, 1 on OOM / native crash; `--version` returns -1).

Alternatives considered: bundling our own JRE (≈ +50 MB, separate update stream); running in-process with an
isolated classloader (violates A4, can't kill on cancel, `--add-opens` not settable at runtime). The launcher approach
keeps "QuPath's own Java, child process" (the intent of A4) with no extra download.

### Q4 — Nested / subfolder jars
- **Nested jars are not loaded.** A `URLClassLoader` (which is what `ExtensionClassLoader` is), given a jar containing
  `tools/inner.jar`, sees the outer jar's resources but **not** the inner jar's resources or its
  `META-INF/services` entries. So spec 02's "payload as resources inside the extension jar" is safe.
- **Loose jars in subfolders of the extensions dir *are* picked up.** The extension manager uses a
  `RecursiveDirectoryWatcher` with a `MAX_SEARCH_DEPTH`. So the payload must **never** be extracted under the
  QuPath extensions/user dir. `%LOCALAPPDATA%\mantis-slideconvert\` (E4) is correct.

### Q5 — Tools on QuPath's runtime
Both tools ran to completion via the launcher on both samples, with the `--add-opens` option for bioformats2raw.
blosc's native DLL loads (Zarr is blosc/lz4). Harmless stderr noise on Java 25 from both tools:
`WARNING: A restricted method … System::load … (OpenCV in b2r, blosc-java in r2o) … Use --enable-native-access=ALL-UNNAMED`.
Add `--enable-native-access=ALL-UNNAMED` to **both** tools' JVM options to silence it. It is a JVM flag, not a tool
argument, so D1 is unaffected.

The classpath split was verified with the launcher: `app.classpath=<common>\*` + `app.classpath=<b2r>\*` runs. Byte-identical
shared jars: **134** (not 135 as spec 00/02 say). bioformats2raw has 138 jars (134 + 4 own), raw2ometiff 139 (134 + 5 own:
`raw2ometiff-0.10.1`, `bioformats2raw-0.12.0`, `zarr-java-0.1.0`, `blosc-java-0.1-1.21.4`, `picocli-4.7.5`).

**Comparison with `reference/outputs`** (custom IFD-level comparer, every tile's compressed bytes):
- Both OME-TIFFs: same IFD count (41 / 29), same dimensions, **every tile byte-identical**, and the channel
  names/colours/IDs in OME-XML are identical.
- Differences: TIFF `NewSubfileType`/`SubfileType` tags, the Software tag and OME-XML metadata such as UUIDs. The size
  differs by under 1 KB.
- **The reference outputs were made with different tool versions:** their Software tag is `OME Bio-Formats 7.3.0`,
  ours is `8.5.0`. Their Zarr also differs in layout: it writes all-fill chunks (8 057 vs 1 655 files), has a different
  JSON key order, and has no `0/0/.zattrs`. Chunk bytes that exist in both are identical.
  ⇒ C3 ("pixel- and channel-metadata-equal") **passes**. Byte equality is not achievable and must not be required.
  Spec 09 already compares pixels and channel metadata, so it is consistent with this.

### Q6 — CLI output in QuPath 0.7
Opened headless (`QuPath script`) with `BioFormatsImageServer`. APOL1 has 4 channels (`DAPI_quad`, `FITC_quad`,
`TRITC_quad`, `Cy5_quad`, UINT8, 17600×12800). PROX1 has 2 channels (`DAPI_quad`, `Cy5_quad`, 45600×66800). Each
channel has distinct, plausible intensities.
A visual check in the GUI by the user is still worthwhile. Spec 09 repeats it on the final build.

### Q7 — Does the defect persist in 0.7?
**Yes.** QuPath 0.7 opens both `.mrxs` with `OpenslideImageServer` as **RGB, 3 channels (Red/Green/Blue)** at
different dimensions (APOL1 16642×12096, PROX1 22708×34630). The fluorescence channels are lost. The project is still
needed.

### Q8 — Memory
Live heap after GC: bioformats2raw peaked at **189–209 MB**, raw2ometiff at **64–72 MB**. Default heap (25 % of RAM)
committed up to 1.5 GB for bioformats2raw, only because heap was available.

Minimum `-Xmx` on PROX1 (larger sample):

| Tool | Fails | Succeeds | Notes |
|------|-------|----------|-------|
| bioformats2raw | 64m (OOM), **96m (native crash, `EXCEPTION_ACCESS_VIOLATION`)** | 128m+ | time ~flat 22–37 s from 128m to 1g |
| raw2ometiff | 32m (OOM) | 48m+ | ~32 s regardless |

Peak working set of the JVM process: ~430–470 MB at `-Xmx192m–256m`, ~1.2–1.4 GB at default/1g.
Heap use is driven by tile size × workers × tile cache (64 tiles), not by slide size. Larger slides should need little
more, but that is untested: both samples are small.
The *proposed* default was `-Xmx2g`; **the user chose the JVM default** (see Decisions). That is ~10× the observed live set and covers bigger slides or
non-default tiles. With parallelFiles = 1 it is well under the RAM of any lab PC. The spec 07 heap guard covers
parallel use. Note the native crash at 96m: too little heap can kill the JVM rather than throw an OOM, so the
runner must treat any non-zero exit as failure (it does).

### Q9 — CLI defaults (from `--help`, 0.12.1 / 0.10.1)

| Setting | bioformats2raw | raw2ometiff |
|---------|----------------|-------------|
| Tile size | `-w/--tile-width` 1024, `-h/--tile-height` 1024 | — |
| Workers | `--max-workers` (alias `--max_workers`) 4 | `--max_workers` 4 |
| Tile cache | `--max-cached-tiles` 64 | — |
| Compression | `-c/--compression` blosc (Zarr: null, zlib, blosc, zstd, gzip) | `--compression` LZW (UNCOMPRESSED, LZW, JPEG, JPEG_2000, JPEG_2000_LOSSY) |
| Quality | — | `--quality` (no default printed) |
| Resolutions | `-r/--resolutions` auto (`--target-min-size`) | — |
| Log level | `--log-level` WARN | `--log-level` WARN |
| Progress | `-p/--progress` | `-p/--progress` |

**Spec 06 error:** its compression enum lists `zlib`. raw2ometiff has no zlib (zlib is a *Zarr* option of
bioformats2raw). Valid output values: Uncompressed, LZW, JPEG, JPEG-2000, JPEG-2000 Lossy.

### Q10 — Output size per compression
Ratio = TIFF bytes / input bytes (`.mrxs` + data folder). Inputs: PROX1 105 MB, APOL1 66 MB.

| Compression | PROX1 (2 ch) | APOL1 (4 ch) |
|-------------|--------------|--------------|
| LZW (default) | 4.41× (465 MB) | 2.93× (192 MB) |
| Uncompressed | **76.1×** (8.0 GB) | **19.8×** (1.3 GB) |
| JPEG | 1.47× | 0.56× |
| JPEG-2000 | 76.1× — *not compressed* | 19.8× — *not compressed* |
| JPEG-2000 Lossy | 76.1× — *not compressed* | 19.8× — *not compressed* |

All runs exited 0 in 8–44 s with a JVM working set of 340–520 MB.

**JPEG-2000 is broken in this toolchain:** both JPEG-2000 modes exit 0 with no warning but write TIFF
`Compression = 1` (uncompressed), byte-for-byte the same size as Uncompressed. JPEG writes `Compression = 7`.
The cause was not investigated: it could be raw2ometiff 0.10.1, a missing codec, or Java 25. **Recommendation:** drop both
JPEG-2000 options from the UI (B6 lists only "compression" generically, so this needs no decision change, but confirm).

**Implication for spec 07:** a size-proportional factor is unreliable for Uncompressed, because sparse slides compress
extremely well in the source format. Uncompressed size is exactly computable instead: Σ over pyramid levels of
W×H×C×bytes ≈ 1.34 × W×H×C×bytes, plus the label/macro images. Width, height and channel count are in the Zarr
`.zarray` after step 1, so the output-volume check can run (or be re-run) **between step 1 and step 2** with the
exact figure.

The Zarr temp size measured with 0.12.1 is a bit *smaller* than the reference (PROX1 623 MB vs 650 MB; APOL1
300 vs 302 MB), with far fewer files (empty chunks are skipped). TEMP_FACTOR 7.5 stays conservative.

## Decisions taken (user, 2026-10-07)

- **A4:** bundle our own JRE in the payload. The QuPath-launcher workaround (Q3) was proven but **not chosen**. It is
  kept above as a fallback in case the JRE's size becomes a problem.
- **E3:** heap = JVM default (no `-Xmx`), not the proposed 2g.
- **E6 (new):** compression choices are LZW / JPEG / Uncompressed.

Q3 findings that still apply with a bundled JRE: scrub `JAVA_TOOL_OPTIONS` & co. from the child env, and kill the
whole process tree.

## Spec impacts (applied 2026-10-07; the rows below assumed the launcher option, so the specs hold the final text)

| Spec | Change |
|------|--------|
| 00 | A4 wording (above). E3: default heap 2g. Shared jars 134, not 135. Note that `reference/outputs` were made with older tool versions (Bio-Formats 7.3.0). |
| 02 | Shared jars 134. `ToolPaths` gains launcher creation: copy `jpackage.app-path` exe + write UTF-8 (no BOM) cfg per tool, with classpath as `app.classpath=<common>\*` + `app.classpath=<tool>\*` lines (verified). Acceptance "`java -cp … --version`" becomes "`<launcher> --version`". |
| 03 | Command lines: no `java.exe`. Per-job JVM options (`-Xmx`, `-Djava.io.tmpdir`, logback config, `--add-opens`, `--enable-native-access=ALL-UNNAMED`) go into a per-job cfg, with the launcher copied into the job workspace. Scrub `JAVA_TOOL_OPTIONS`/`_JAVA_OPTIONS`/`JDK_JAVA_OPTIONS` from the child env. Kill the tree (the JVM is a grandchild-like child of the launcher). Peak-memory accounting is on the child, not the launcher. |
| 06 | Compression enum: remove zlib; drop JPEG-2000 and JPEG-2000 Lossy (silently uncompressed), leaving LZW / JPEG / Uncompressed. Tile 1024×1024, workers 4. heapPerFile default 2g, min 256m (128m is the measured floor; below it, native crashes). |
| 07 | Uncompressed output check: compute from dimensions after step 1 (≈1.34·W·H·C·bytes + label/macro), not a factor. JPEG factor 1.5× measured; use 2.0× with margin. LZW unchanged. |
| 09 | Byte equality with `reference/outputs` is impossible (different Bio-Formats version). Compare tiles + channel metadata only, which is what 09 already says. |
| 10 | JDK 25 toolchain; resolve Gradle template version for 0.7. No JDK on the dev machine yet. |
