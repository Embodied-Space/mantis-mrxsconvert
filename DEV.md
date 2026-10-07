# Developer Guide

Reference for people working on the extension. For end-user docs see [README.md](README.md).

## The problem in one paragraph

QuPath shows the channels of fluorescence `.mrxs` (3DHISTECH/MIRAX) slides incorrectly. Running Glencoe's
**bioformats2raw 0.12.1 → raw2ometiff 0.10.1** (Bio-Formats 8.5.0) produces an OME-TIFF that QuPath reads
correctly. This extension wraps that two-step command line in a QuPath dialog for non-technical users. It only
converts files: it doesn't import them into QuPath projects, carry over annotations or touch the originals.

## Status

Spike 01 is done (`specs/01-findings.md`). The extension adds *Extensions › Convert MRXS to OME-TIFF…*.
Spec 02 (tool payload) is done: the build bundles the JRE and both tools, and `ToolPayload.ensureExtracted()` unpacks
them. Spec 03 (conversion runner) is done: `ConversionRunner.convert(...)` converts one slide, with temp workspaces
from spec 05 (`TempWorkspace`, orphan cleanup at startup). Spec 04 (`InputPlanner.plan(...)`), spec 06
(`ConversionSettings`, `SettingsStore`, `MachineLimits`) and spec 07 (`BatchQueue.start(...)`) are done. Spec 08
(UI) is implemented; its manual acceptance script in QuPath is still to be run. Spec 10 (release) is implemented;
its first CI run, tagged release and fresh-install check are pending. Spec 09 (acceptance harness) is next.

## Building

Needs JDK 25. `JAVA_HOME` must point at it:

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1
gradlew build
```

The installable jar is `build\libs\mantis-mrxsconvert-<version>-all.jar` (~266 MB); `gradlew build` produces it (QuPath's own libraries are not bundled).
The Gradle wrapper download is pinned by SHA-256 in `gradle/wrapper/gradle-wrapper.properties`.

The version is `0.1.0-SNAPSHOT` unless you pass `-PreleaseVersion=<version>`.

## CI and releases

`.github/workflows/build.yml` runs `gradlew.bat build` on `windows-latest` for every push to `main` and every PR,
and uploads the `-all` jar as a workflow artifact (kept 14 days). There is no `reference/` on CI, so the conversion
tests are skipped; `ToolPayloadIntegrationTest` still runs the bundled JRE and both tools. The pinned downloads are
cached by the workflow (key: hash of `build.gradle.kts`) and their SHA-256s are checked on every build.

To release, push a tag `v<version>` (for example `git tag v0.1.0 && git push origin v0.1.0`). The build uses
`<version>` as the jar version, and the `release` job creates a GitHub Release with the jar and
`.github/release-notes.md` as its text. A tag containing `-` (`v0.2.0-rc1`) becomes a pre-release. Before a release,
check the versions in the release notes against spec 02. Actions are pinned by commit SHA, with the tag in a comment.

The first build downloads the pinned tools and JRE (~470 MB, SHA-256-checked) into `.gradle\payload-downloads\`,
which survives `gradlew clean`. Pins (URL + SHA-256) are in `build.gradle.kts` and listed in spec 02. Build chain:
`download*` → `assemblePayload` (`build\payload\`) → `shadowJar` (code only, `-code` jar) → `extensionJar` (`-all` jar).
`gradlew test` builds the `-all` jar first: `ToolPayloadIntegrationTest` extracts the payload from it and runs
the bundled `java.exe` and both tools. `ConversionRunnerIntegrationTest` converts the `reference/` samples for real
(success, step-1 failure, cancel in each step; ~1 min) and is skipped when `reference/` is missing. It keeps its
extracted payload in `%TEMP%\mantis-mrxsconvert-test-tools\` between runs (not under `build\`: an IDE watching the
folder can block the final rename of a fresh extraction).

## Repository layout

```
README.md      end-user intro
LICENSE        GPL-3.0 (also bundled in the jar as licenses/LICENSE)
DEV.md         this file
CLAUDE.md      instructions for Claude Code (short version of this file)
build.gradle.kts, settings.gradle.kts, gradle/, gradlew*   Gradle build (versions pinned in spec 10)
.github/       CI workflow + release notes (spec 10)
src/main/java/qupath/ext/mantis/mrxsconvert/              extension code
specs/         design: 00 = goal + decision ledger, 01–10 = one component each
reference/     local-only, git-ignored (~2.2 GB) — see "Test data"
```

## How specs work

- `specs/00-goal-and-decisions.md` is the **source of truth**. Every decision has an ID (A1–E6), and each
  was explicitly confirmed by the project owner.
- Each spec covers one component and lists its scope, what's out of scope, its interface and its acceptance
  criteria. Keep them small.
- To change a decision, agree it with the owner first. Then update the ledger in spec 00 and every spec
  that cites that ID.

Build order: `01 → 02 → 03 → 07 → 08 → 10`. Specs 04, 05 and 06 are pure logic and can be built in
parallel. Spec 09 can start once 03 works.

## Architecture

```
QuPath (JavaFX)            ── never loads the tool jars ──
  └─ Extension
       ├─ 08 UI ─► 07 Batch queue ─► 03 Runner ─┬─► child JVM: bioformats2raw  (.mrxs → Zarr in %TEMP%)
       │            ▲   ▲   ▲                    └─► child JVM: raw2ometiff     (Zarr → .ome.tiff.partial → rename)
       │      04 Planning  05 Temp  06 Settings
       └─ 02 Payload: JRE + tool jars nested in the extension jar → extracted to %LOCALAPPDATA%\mantis-mrxsconvert\tools\<hash>\
```

- **Child processes, not in-process.** QuPath ships its own Bio-Formats. Loading 8.5.0 next to it causes
  classpath conflicts, and those conflicts are the source of the bug. The child JVMs run on a **JRE bundled in our
  payload**, because QuPath 0.7.0's runtime is jlink'd without `java.exe` (spike 01).
- **Two separate classpaths.** 134 jars are byte-identical between the tools and go in `common/`. But
  raw2ometiff pins older `bioformats2raw-0.12.0`, `zarr-java-0.1.0`, `blosc-java-0.1-1.21.4` and
  `picocli-4.7.5`, so each tool also gets its own folder (`b2r/`, `r2o/`).
- **JVM flags.** bioformats2raw needs `--add-opens java.base/java.lang=ALL-UNNAMED`; raw2ometiff needs nothing extra.
  On Java 25 both print native-access warnings; `--enable-native-access=ALL-UNNAMED` silences them.
- **Main classes.** `com.glencoesoftware.bioformats2raw.Converter` and
  `com.glencoesoftware.pyramid.PyramidFromDirectoryWriter`.
- **Native code.** The `blosc-java` jars include `libbloscjni.dll`, so nothing extra is needed on Windows.
- **Runner.** Per slide: a temp workspace `%TEMP%\mantis-mrxsconvert\<uuid>\` (held by a lock on `owner.lock`, so
  startup cleanup can tell live from leftover), step 1 → Zarr in the workspace, step 2 → `<output>.partial`, then a
  non-replacing rename. A hidden `<output>.partial.lock` is held for the whole job; a `.partial` with a free lock is a
  crash leftover and gets deleted. The child JVMs run with the workspace as working dir and a scrubbed environment. Each job
  appends tool output, command lines and exit codes to its log file. Failure reasons are user-facing strings in
  `strings.properties` (`runner.failed.*`).
- **Planning → batch.** `InputPlanner` turns selections into a `Plan` (READY / SKIP_EXISTS / INVALID + blocking
  clashes). `BatchQueue` runs the READY jobs on `parallelFiles` daemon threads. Before each one, `DiskSpaceGuard`
  reserves the spec 07 factors × input size on the temp and output volumes, minus what running jobs hold. For
  Uncompressed output, a `StepGate` re-checks the exact size from the Zarr (`ZarrSize`) before step 2. `BatchHandle`
  gives per-job `JobState`, `cancel()` and a `BatchReport` future. A `BatchListener` hears about changes on batch
  threads, so the UI must hop to the FX thread. Settings persist through `SettingsStore` (QuPath preferences,
  `mantisSlideconvert.*`).
- **UI.** `ConverterWindow` is one non-modal window owned by QuPath's main window. It shows `SetupView` (added
  files/folders, the planned jobs, output choice, `AdvancedSettingsPane`, Start) or the `BatchView` of the current batch
  (progress, then summary with *Show log*). `SetupView` re-plans on a background thread after every change and ignores
  stale results. `BatchView` coalesces listener calls into one `Platform.runLater` refresh. Closing the window never
  cancels. An event filter on QuPath's main window catches quit (window close and *File › Quit*) during a batch: it
  asks, cancels, and quits again once the batch has stopped. All text is in `strings.properties` (`ui.*`), formatted by
  `UiText`, which is unit-tested; JavaFX isn't on the test compile classpath.
- **Payload.** The JRE and tool jars are plain resources under `qupath/ext/mantis/mrxsconvert/payload/` in the
  `-all` jar, with a `manifest.json` (paths + SHA-256s). They are added by a plain `Jar` task, never by Shadow:
  Shadow unpacks every `.jar` it is given and would put the tools' classes on QuPath's classpath.
  At runtime they are extracted to `%LOCALAPPDATA%\mantis-mrxsconvert\tools\<id>\` (~385 MB). File locks
  (`<id>.lock`, `<id>.inuse`) keep several QuPath instances safe; see spec 02.

## The manual process (what we automate)

```bat
bioformats2raw.bat "slide.mrxs" "%TEMP%\work\pyramid"
raw2ometiff.bat    "%TEMP%\work\pyramid" "slide.ome.tiff"
```

The launchers are in `reference\<tool>\<tool>\bin\`. They need a `java.exe` (via `JAVA_HOME` or `PATH`).
QuPath's runtime has none, so use any Java 11+; the extension uses its bundled JRE. Measurements and details are in
`specs/01-findings.md`.

## Test data (`reference/`, never committed)

| Scan (`reference/original scans/`) | Channels | Known-good output (`reference/outputs/`) |
|---|---|---|
| `APOL1_650_APOL1_570_Nephrin_520_MTS 43.1 A_2` | 4: DAPI, FITC, TRITC, Cy5 | `4colorpyramid.ome.tiff` (Zarr: `4colortest/`) |
| `PROX1_500_Mm lung_MCT 1.2 A_8` | 2: DAPI, Cy5 | `2colorpyramid.ome.tiff` (Zarr: `2colortest/`) |

- Each `.mrxs` is a small index file. It only works with its sibling data folder (`Slidedat.ini`, `Index.dat`,
  `Data*.dat`), so keep the two together.
- The `._*.mrxs` files in there are macOS metadata, not slides, and discovery must ignore them (E5).
- Acceptance (spec 09): with default settings, our output must match these pixel for pixel, with the same
  channel metadata. Bytes can differ (UUIDs, timestamps).
- Size ratios from these samples determine the disk-space check (spec 07): Zarr is up to 5.9× the input and
  the TIFF up to 4.4×.

## Conventions and gotchas

- With default settings, pass **no** optional CLI arguments, so the output stays identical to the manual process.
- Never overwrite an existing output. Write `*.ome.tiff.partial`, then rename it on success.
- Always delete the temp Zarr. On cancel, kill the child's whole process tree, not just the parent process.
- Slide paths contain spaces and dots. Pass them to `ProcessBuilder` as separate arguments, never as one shell string.
- Never block the JavaFX thread.
- The platform is Windows only. Don't add macOS or Linux code paths.
- The license is GPL-3.0, because the bundled Bio-Formats includes GPL components. Keep the third-party
  notices in the payload's `LICENSES/` folder.

## Glossary

| Term | Meaning |
|---|---|
| `.mrxs` | 3DHISTECH/MIRAX slide format: an index file plus a data folder |
| Zarr / NGFF | Chunked array format; bioformats2raw's intermediate output |
| OME-TIFF | TIFF with OME-XML metadata; tiled and pyramidal here |
| Pyramid | The same image stored at several resolutions for fast zooming |
| Label / macro | Small overview images of the slide label and the whole glass; kept in the output (B5) |
