# 02 — Tool Payload

**Depends on:** 01. **Decisions:** A4, A5.

## Purpose

Get both CLI tools inside the extension jar, small as practical, and onto disk at runtime as two runnable classpaths.

## Scope

- **Build step:** from `reference/` produce a payload layout:
  ```
  tools/
    common/   # the 135 byte-identical jars
    b2r/      # bioformats2raw-0.12.1, zarr-java-0.1.3, blosc-java-0.3-1.21.6, picocli-4.7.6, config/
    r2o/      # raw2ometiff-0.10.1, bioformats2raw-0.12.0, zarr-java-0.1.0, blosc-java-0.1-1.21.4, picocli-4.7.5, config/
    manifest.json   # file list + SHA-256 + tool versions
    LICENSES/       # tool LICENSE.txt files + third-party notices
  ```
  Dedup is by **byte equality**, verified at build time (fail build if a "common" jar differs).
- Payload stored as resources inside the extension jar (not as loose jars QuPath could load — see 01 Q4).
- **Runtime:** `ToolPayload.ensureExtracted() -> ToolPaths`
  - Target: `%LOCALAPPDATA%\mantis-slideconvert\tools\<payload-hash>\`.
  - Extract to a sibling temp dir, verify SHA-256s, atomic rename into place. Concurrent QuPath instances must not corrupt it.
  - If target exists and a `.complete` marker is present, reuse without re-hashing.
  - Old `<payload-hash>` dirs may be deleted when not in use (best-effort).
- `ToolPaths` exposes: `classpathB2R` (`common\*;b2r\*`), `classpathR2O` (`common\*;r2o\*`), `logbackConfigB2R`, `logbackConfigR2O`.

## Out of scope

Running the tools (03). Downloading anything. Non-Windows paths.

## Acceptance

- Built extension jar < ~220 MB.
- Fresh machine: first call extracts and returns valid paths; second call is instant.
- Corrupting one extracted jar → next call detects (on missing marker) or re-extract path works.
- Two QuPath instances calling simultaneously → one valid payload dir, no errors.
- `java -cp <classpathB2R> com.glencoesoftware.bioformats2raw.Converter --version` succeeds; same for R2O.
