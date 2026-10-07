# 00 — Goal & Decision Ledger

**Status:** Approved (interview 2026-10-07). This file is the source of truth; other specs reference IDs below.

## Real goal

QuPath imports fluorescence `.mrxs` slides incorrectly (channels wrong). Converting them to OME-TIFF with
**bioformats2raw 0.12.1 → raw2ometiff 0.10.1** (Bio-Formats 8.5.0) produces files QuPath reads correctly.
Today this is a manual two-step CLI process. The extension lets **non-technical lab users** do it from inside
QuPath, for single files, multiple files, or whole directories.

## Non-goals

- Importing converted files into a QuPath project, migrating annotations, or deleting/moving originals.
- Using QuPath's own OME-TIFF writer (it reads through QuPath's broken `.mrxs` path).
- Drag-and-drop, Groovy scripting API.
- macOS / Linux.
- Converting formats other than `.mrxs`.

## Decision ledger (all explicitly confirmed by the user)

### A. Goal & architecture
| ID | Decision |
|----|----------|
| A1 | Pipeline is bioformats2raw 0.12.1 → raw2ometiff 0.10.1, run outside QuPath's readers. |
| A2 | Users are non-technical; never see a terminal. Scope is conversion only. |
| A3 | Target QuPath 0.7.x (confirmed by spike 01: go). Windows x64 only. |
| A4 | Each tool runs as a **child JVM** using a **Java runtime bundled in our payload** (its `java.exe`). Never in-process. *(Revised 2026-10-07: spike 01 found QuPath 0.7.0's runtime has no `java.exe`; user chose to bundle a JRE over reusing QuPath's launcher.)* |
| A5 | Tools **and the JRE** bundled inside the single extension `.jar`, extracted on first run to a versioned cache. GPL-3.0, public GitHub releases. |

### B. Behavior
| ID | Decision |
|----|----------|
| B1 | Entry point: Extensions menu → dialog (add files / add folder, list, output folder, Advanced, Start). |
| B2 | Folder selection is scanned **recursively** for `.mrxs`. |
| B3 | Output next to source by default. Optional output-folder override; folder selections mirror their sub-tree beneath it. |
| B4 | Existing output → **skip**. A failure does not stop the batch; end-of-batch summary lists per-file outcome + log. |
| B5 | Keep label/macro images (CLI default behavior). |
| B6 | Advanced: parallel files (default **1**), heap per file, compression (LZW / JPEG / Uncompressed — see E6), tile size, resolutions, max_workers. Settings + last output folder persist; "Reset to defaults" button. |
| B7 | Runs in background; QuPath stays usable; progress window with Cancel. |

### C. Operations
| ID | Decision |
|----|----------|
| C1 | Intermediate Zarr goes in **system temp**; always deleted (success, failure, cancel). |
| C2 | Pre-flight free-space check per file; orphaned temp dirs cleaned at QuPath startup. |
| C3 | Acceptance: with default settings, output is pixel- and channel-metadata-equal to the user's known-good CLI outputs, on the user's fluorescence samples. |

### D. Inferred defaults (approved)
| ID | Decision |
|----|----------|
| D1 | Default Advanced values = CLI tools' own defaults (so C3 can pass). |
| D2 | Individually picked files under an override folder land flat; any two jobs resolving to the same output path block Start until resolved. |
| D3 | Insufficient space → that file fails, batch continues. Requirement (revised 2026-10-07 from `reference/` data): 7.5× input on temp volume + 5.5× on output volume (13× if same volume), plus 1 GiB headroom each. Details in spec 07. |
| D4 | Cancel kills running child process trees, deletes partial outputs + temp, marks queued as cancelled, keeps completed outputs. |
| D5 | Output written as `<name>.ome.tiff.partial`, then renamed on success. |
| D6 | Temp dirs carry an owner marker (PID + lock) so startup cleanup never deletes another live QuPath's temp. |
| D7 | Any `.mrxs` is converted (brightfield too); no content-type check. |
| D8 | `.mrxs` missing its companion data folder fails fast with a clear message, before any work. |
| D9 | One log file per conversion, stored in the extension's cache folder (`%LOCALAPPDATA%\mantis-slideconvert\logs\`). |

### E. Decided during spec writing (confirmed)
| ID | Decision |
|----|----------|
| E1 | Override + folder selection mirrors the tree **including** the selected folder's name: `<override>\<Selected>\sub\x.ome.tiff`. |
| E2 | Build downloads pinned tool releases (SHA-256 verified); `reference/` is local-only and git-ignored. |
| E3 | Default heap per child JVM = **JVM default** (no `-Xmx`; 25 % of physical RAM). Spike 01 measured live heap ≈ 210 MB (b2r) / 70 MB (r2o); floor 128 MB / 48 MB. *(Decided 2026-10-07.)* |
| E4 | Extracted tools + logs live in `%LOCALAPPDATA%\mantis-slideconvert\`. |
| E5 | Discovery ignores macOS artifacts: `._*` AppleDouble files, `.DS_Store`, `__MACOSX/` folders. Never shown, never counted. |
| E6 | Output compression choices: LZW (default), JPEG, Uncompressed. JPEG-2000 / JPEG-2000 Lossy are excluded (spike 01: they silently write uncompressed TIFFs); zlib is not a raw2ometiff option. *(Decided 2026-10-07.)* |

## Spec map & build order

```
00 ─► 01 (gate) ─► 02 ─► 03 ─┬─► 07 ─► 08 ─► 10
                              │    ▲
       04, 05, 06 (pure) ─────┴────┘
                         03 ─► 09
```

| # | Spec |
|---|------|
| 01 | Spike: QuPath 0.7 runtime (gate) |
| 02 | Tool payload |
| 03 | Conversion runner |
| 04 | Input planning |
| 05 | Temp hygiene |
| 06 | Settings |
| 07 | Batch queue |
| 08 | UI |
| 09 | Acceptance harness |
| 10 | Release |

## Facts discovered in `reference/` (inputs to specs 02/03)

- Main classes: `com.glencoesoftware.bioformats2raw.Converter`, `com.glencoesoftware.pyramid.PyramidFromDirectoryWriter`.
- bioformats2raw needs JVM opt `--add-opens java.base/java.lang=ALL-UNNAMED`; raw2ometiff needs none.
- Both require Java 11+. Both verified on Java 25.0.2 (spike 01).
- 134 jars are byte-identical between the tools (b2r has 138, r2o 139; spike 01 recount). They **cannot share one classpath**: raw2ometiff
  pins `bioformats2raw-0.12.0`, `zarr-java-0.1.0`, `blosc-java-0.1-1.21.4`, `picocli-4.7.5`; bioformats2raw uses
  `0.12.1`, `0.1.3`, `0.3-1.21.6`, `4.7.6`.
- `blosc-java` jars carry their own `libbloscjni.dll` (Windows x64 covered).
- Each tool ships a `lib/config/` (logback config).
- `reference/outputs` were made with **older tool versions** (Software tag `OME Bio-Formats 7.3.0`). With the pinned tools,
  every tile and the channel metadata are identical, but files are not byte-identical (spike 01 Q5). Compare pixels + channels, never bytes.
