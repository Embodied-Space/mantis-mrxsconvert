# 06 — Settings

**Depends on:** nothing (persistence API confirmed in 01 Q2). **Decisions:** B6, D1.

## Purpose

One immutable settings model, its CLI mapping, and persistence. No UI.

## Fields

| Field | Default | Maps to | Notes |
|-------|---------|---------|-------|
| parallelFiles | 1 | (07 concurrency) | min 1, max = cores |
| heapPerFile | **Decided in 01 (E3)** from measured peak memory on user samples | `-Xmx` on both child JVMs | Not a CLI default; validate vs physical RAM |
| compression | CLI default (LZW) | raw2ometiff `--compression` | enum: LZW, JPEG, JPEG-2000, JPEG-2000 Lossy, zlib, Uncompressed |
| compressionQuality | CLI default | raw2ometiff `--quality` | only for JPEG / JPEG-2000 Lossy |
| tileWidth / tileHeight | CLI default (read from `--help` in 01) | bioformats2raw `--tile-width/--tile-height` | |
| resolutions | auto (CLI default) | bioformats2raw `--resolutions` | auto = omit arg |
| maxWorkers | CLI default | `--max_workers` on both tools | |
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
