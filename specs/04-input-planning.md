# 04 — Input Planning

**Depends on:** nothing (pure logic + filesystem reads). **Decisions:** B2, B3, B4, D2, D7, D8.
**Status:** Done (2026-10-07). Code: `plan/InputPlanner` (+ `Selection`, `Plan`, `PlannedJob`). Tests: `InputPlannerTest`.
Details settled in code (no ledger change): a slide reached by several selections takes its output from the **first**
one; a data folder without `Slidedat.ini` is INVALID too; a picked file that is missing or not `.mrxs` is INVALID; a
selected folder that no longer exists is a blocking problem; unreadable sub-folders are logged and skipped; an
INVALID job has no output and can't clash. Messages are `plan.*` in `strings.properties`.

## Purpose

Turn the user's selections into a validated list of `(input, output)` jobs before anything runs.

## Interface (sketch)

```java
Plan plan(List<Selection> selections, Optional<Path> outputOverride);
// Selection = File(path) | Folder(path)
// Plan = List<PlannedJob>, List<Problem>
// PlannedJob = input, output, status: READY | SKIP_EXISTS | INVALID(reason)
// Problem = blocking issue (e.g. output clash) -> Start disabled
```

## Rules

1. **Discovery:** `Folder` → recursive walk for `*.mrxs` (case-insensitive). `File` → itself. Deduplicate by normalized absolute path.
   **Ignore macOS artifacts (E5):** files named `._*` (AppleDouble, e.g. `._slide.mrxs`), `.DS_Store`, and
   anything under a `__MACOSX` folder. Applies to folder walks *and* explicit file picks; ignored items are
   silently dropped (no job, no INVALID entry).
2. **Validation (D8):** `<name>.mrxs` requires sibling directory `<name>/` (contains `Slidedat.ini`). Missing → `INVALID("missing data folder <path>")`.
3. **Output mapping (B3, D2):**
   - No override → `<same dir>\<name>.ome.tiff`.
   - Override + from `Folder(root)` → `<override>\<root folder name>\<rel dir>\<name>.ome.tiff` (E1).
     e.g. `D:\scans\Batch7\sub\x.mrxs` + override `E:\out` → `E:\out\Batch7\sub\x.ome.tiff`.
   - Override + from `File` → `<override>\<name>.ome.tiff`.
4. **Clash detection (D2):** two jobs with the same normalized output path → blocking `Problem` listing both inputs.
5. **Skip (B4):** output already exists → `SKIP_EXISTS`. A leftover `.partial` does **not** count as existing.
6. No type check on content (D7).

## Out of scope

Running, disk-space checks (07), UI rendering (08).

## Acceptance (unit-testable with temp dirs)

- Nested folders discovered; non-`.mrxs` ignored; `.MRXS` accepted.
- `reference/original scans/` → exactly 2 jobs; `._PROX1_500_Mm lung_MCT 1.2 A_8.mrxs` produces nothing.
- `x.mrxs` inside `__MACOSX/` → ignored.
- Same file via folder + explicit pick → one job.
- Missing data folder → INVALID with path in message.
- Mirror under override preserves sub-tree; flat picks land flat.
- Constructed clash → blocking problem naming both sources.
- Existing output → SKIP_EXISTS; existing `.partial` only → READY.
