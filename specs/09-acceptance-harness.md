# 09 — Acceptance Harness

**Depends on:** 03. **Decisions:** C3, D1.

## Purpose

Prove the extension's output is equivalent to the manual CLI process on real fluorescence slides.

## Inputs (provided by user, not committed)

- Fluorescence `.mrxs` samples (+ data folders).
- Known-good `.ome.tiff` produced by the manual CLI process with default settings.
- Location configured via env var / Gradle property (e.g. `MANTIS_TESTDATA`); tests skip with a clear message if absent.

## Comparison ("pixel + channel metadata equal")

For each sample, extension output vs known-good:
1. Same series count; for each series: same SizeX/Y/Z/C/T, pixel type, resolution-level count and dimensions.
2. Full-resolution pixels identical for every channel (tile-wise compare; stream, don't load whole planes).
   Lower pyramid levels: identical too (same tool, same defaults) — if not, report but treat as a finding to discuss.
3. OME-XML: same channel count, names, emission/excitation, colors; same physical pixel sizes.
4. Ignored: UUIDs, timestamps, file sizes/bytes, TIFF `Software`, `NewSubfileType`/`SubfileType` tags.

**Known:** the user's known-good files in `reference/outputs` were made with older tool versions (`OME Bio-Formats 7.3.0`),
so byte equality is impossible. Spike 01 found every tile (all pyramid levels) and all channel metadata identical, so
criteria 1–3 are expected to pass as written.

Read with Bio-Formats 8.5.0 (the payload's `common/` jars) in the test JVM.

## Also

- One brightfield-style or public `.mrxs` smoke test (D7) if available: converts without error.
- Manual check: open output in QuPath 0.7; channels correct (repeat of 01 Q6 on final build).

## Acceptance

All user fluorescence samples pass 1–3 with default settings.
