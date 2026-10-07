# mantis_slideconvert

QuPath extension (Windows, GPL-3.0) that converts fluorescence `.mrxs` slides to `.ome.tiff` by running
bioformats2raw 0.12.1 → raw2ometiff 0.10.1 as child JVMs on QuPath's bundled Java. QuPath reads `.mrxs`
fluorescence channels incorrectly; the CLI output reads correctly. Users are non-technical: GUI only.

## Folder structure
- `README.md` — end-user intro (keep brief, no dev detail). `DEV.md` — human developer reference; keep in sync.
- `specs/` — source of truth. `00-goal-and-decisions.md` holds the confirmed decision ledger (IDs A1–E4);
  `01`–`10` are small specs, one component each, with acceptance criteria.
- `reference/` — local-only test material, ~2.2 GB, **never commit** (E2):
  - `bioformats2raw-0.12.1/`, `raw2ometiff-0.10.1/` — unpacked CLI distributions (`bin/`, `lib/`).
  - `original scans/` — sample `.mrxs` files, each with its same-named data folder:
    `APOL1_650_APOL1_570_Nephrin_520_MTS 43.1 A_2` (4 ch: DAPI/FITC/TRITC/Cy5),
    `PROX1_500_Mm lung_MCT 1.2 A_8` (2 ch: DAPI/Cy5).
  - `outputs/` — known-good manual CLI results: `4colorpyramid.ome.tiff` (APOL1), `2colorpyramid.ome.tiff`
    (PROX1), plus the intermediate Zarr dirs `4colortest/`, `2colortest/` (useful for testing step 2 alone).

## Current status
Nothing is built yet. Next up is **spec 01, the spike (a gate)**: confirm QuPath 0.7's Java and extension API, check
whether nested jars get loaded, launch the tools via QuPath's `java.exe`, measure heap, read the defaults from `--help`.
Write the answers to `specs/01-findings.md`. Don't start specs 02–10 until 01 is done and any spec it invalidates is revised.

## Common mistakes to avoid
- **Never run the tools in QuPath's JVM or put their jars on its classpath.** Bio-Formats 8.5 conflicts with
  QuPath's copy, and that conflict is what causes the bug (A4).
- **Don't merge the two tools' classpaths.** raw2ometiff pins older bioformats2raw/zarr-java/blosc-java/picocli
  jars. Share only the 135 byte-identical jars (spec 02).
- bioformats2raw needs `--add-opens java.base/java.lang=ALL-UNNAMED`; raw2ometiff doesn't.
- `.mrxs` is not a single file: it needs its sibling data folder (`Slidedat.ini`, `Data*.dat`). Never move one without the other.
- Ignore macOS junk everywhere (E5): `._*` AppleDouble files (e.g. `._PROX1….mrxs` is NOT a slide), `.DS_Store`, `__MACOSX/`.
- Paths contain spaces and dots; always pass them as separate process args, never through a shell string.
- Outputs are far larger than inputs (110 MB scan → 650 MB Zarr + 487 MB TIFF). Disk check factors (7.5× temp,
  5.5× output, +1 GiB headroom) are calibrated in spec 07. Recalibrate there, don't hard-code elsewhere.
- With default settings, emit **no** optional CLI args, so output matches `reference/outputs` (D1, C3).
- Never overwrite existing outputs (skip); write `*.ome.tiff.partial`, then rename (B4, D5).
- Always delete temp Zarr, and kill the whole child process tree on cancel (C1, D4).
- Keep long work off the JavaFX thread.
- Don't change a confirmed decision on your own. Raise it with the user and update spec 00.
- **Never run `git commit`** (or anything that creates commits: amend, merge, rebase, cherry-pick). The user commits.
