# Developer Guide

Reference for people working on the extension. For end-user docs see [README.md](README.md).

## The problem in one paragraph

QuPath shows the channels of fluorescence `.mrxs` (3DHISTECH/MIRAX) slides incorrectly. Running Glencoe's
**bioformats2raw 0.12.1 → raw2ometiff 0.10.1** (Bio-Formats 8.5.0) produces an OME-TIFF that QuPath reads
correctly. This extension wraps that two-step command line in a QuPath dialog for non-technical users. It only
converts files: it doesn't import them into QuPath projects, carry over annotations or touch the originals.

## Status

Spike 01 is done (`specs/01-findings.md`). The Gradle project is scaffolded: the extension registers itself and adds a
placeholder menu item. Next is spec 02 (tool payload).

## Building

Needs JDK 25. `JAVA_HOME` must point at it:

```bat
set JAVA_HOME=C:\Program Files\Java\jdk-25.0.4.1
gradlew build
```

The installable jar is `build\libs\mantis-mrxsconvert-<version>-all.jar`; `gradlew build` produces it (QuPath's own libraries are not bundled).
The Gradle wrapper download is pinned by SHA-256 in `gradle/wrapper/gradle-wrapper.properties`.

## Repository layout

```
README.md      end-user intro
DEV.md         this file
CLAUDE.md      instructions for Claude Code (short version of this file)
build.gradle.kts, settings.gradle.kts, gradle/, gradlew*   Gradle build (versions pinned in spec 10)
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
