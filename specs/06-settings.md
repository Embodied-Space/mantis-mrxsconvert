# 06 — Settings

**Depends on:** nothing (persistence API confirmed in 01 Q2). **Decisions:** B6, D1.
**Status:** Model + CLI mapping done with spec 03 (2026-10-07): `settings/ConversionSettings` (null = Auto/CLI default),
`settings/Compression`, `ConversionSettingsTest`. **Persistence and machine-dependent limits (cores, RAM) still to do.**

## Purpose

One immutable settings model, its CLI mapping, and persistence. No UI.

## Fields

| Field | Default | Maps to | Notes |
|-------|---------|---------|-------|
| parallelFiles | 1 | (07 concurrency) | min 1, max = cores |
| heapPerFile | **Auto** = JVM default, no `-Xmx` (E3) | `-Xmx` on both child JVMs | When set: min 256 MB (spike 01 floor is 128 MB; below it the JVM can crash natively), max = physical RAM |
| compression | CLI default (LZW) | raw2ometiff `--compression` | enum (E6): LZW, JPEG, Uncompressed → `LZW`, `JPEG`, `UNCOMPRESSED` |
| compressionQuality | CLI default (none printed by `--help`) | raw2ometiff `--quality` | only for JPEG. raw2ometiff parses it as a double into Bio-Formats `CodecOptions.quality`; model accepts (0, 1] |
| tileWidth / tileHeight | 1024 / 1024 (spike 01) | bioformats2raw `--tile-width/--tile-height` | |
| resolutions | auto (CLI default) | bioformats2raw `--resolutions` | auto = omit arg |
| maxWorkers | 4 (both tools, spike 01) | `--max_workers` on both tools (b2r also accepts `--max-workers`) | |
| lastOutputFolder | empty (= next to source) | — | |

**Rule (D1):** an arg is emitted only when the value differs from the CLI default. Default settings ⇒ identical command lines to the manual process ⇒ C3 holds.

## Persistence

QuPath preferences (namespaced keys `mantisSlideconvert.*`), loaded on dialog open, saved on Start.
`resetToDefaults()` restores the table above (output folder included).

## Acceptance

- Defaults produce **no** optional CLI args.
- Each changed field produces exactly its documented arg(s).
- Values survive QuPath restart; Reset restores defaults.
- Invalid persisted values (e.g. heap > RAM) fall back to defaults with a log warning.
