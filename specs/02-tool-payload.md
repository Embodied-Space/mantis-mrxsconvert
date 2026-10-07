# 02 — Tool Payload

**Depends on:** 01. **Decisions:** A4, A5.

## Purpose

Get both CLI tools **and a Java runtime** inside the extension jar, small as practical, and onto disk at runtime as
one `java.exe` plus two runnable classpaths.

## Scope

- **Build step:** from `reference/` produce a payload layout:
  ```
  tools/
    jre/      # pinned Windows x64 JRE (see below), unpacked so jre\bin\java.exe exists
    common/   # the 134 byte-identical jars
    b2r/      # bioformats2raw-0.12.1, zarr-java-0.1.3, blosc-java-0.3-1.21.6, picocli-4.7.6, config/
    r2o/      # raw2ometiff-0.10.1, bioformats2raw-0.12.0, zarr-java-0.1.0, blosc-java-0.1-1.21.4, picocli-4.7.5, config/
    manifest.json   # file list + SHA-256 + tool versions
    LICENSES/       # tool LICENSE.txt files + third-party notices
  ```
  Dedup is by **byte equality**, verified at build time (fail build if a "common" jar differs).
- **JRE (A4, revised):** QuPath 0.7.0's runtime has no `java.exe` (spike 01 Q3), so we ship our own.
  Confirmed (user, 2026-10-07): Eclipse Temurin **JRE 25** Windows x64 zip (the tools were verified on Java 25.0.2), pinned by version +
  SHA-256 like the tools (E2). Licence GPLv2 + Classpath Exception; notice goes in `LICENSES/`.
  Must include at least the modules spike 01 proved sufficient: `java.se`, `jdk.unsupported`, `jdk.zipfs`.
- Payload stored as resources inside the extension jar (not as loose jars QuPath could load — see 01 Q4).
- **Runtime:** `ToolPayload.ensureExtracted() -> ToolPaths`
  - Target: `%LOCALAPPDATA%\mantis-mrxsconvert\tools\<payload-hash>\`.
  - Extract to a sibling temp dir, verify SHA-256s, atomic rename into place. Concurrent QuPath instances must not corrupt it.
  - If target exists and a `.complete` marker is present, reuse without re-hashing.
  - Old `<payload-hash>` dirs may be deleted when not in use (best-effort).
- `ToolPaths` exposes: `javaExe` (`jre\bin\java.exe`), `classpathB2R` (`common\*;b2r\*`), `classpathR2O` (`common\*;r2o\*`), `logbackConfigB2R`, `logbackConfigR2O`.

## Out of scope

Running the tools (03). Downloading anything. Non-Windows paths.

## Acceptance

- Built extension jar < ~280 MB (tools ≈ 205 MB deduplicated + JRE ≈ 45–50 MB).
- Fresh machine: first call extracts and returns valid paths; second call is instant.
- Corrupting one extracted jar → next call detects (on missing marker) or re-extract path works.
- Two QuPath instances calling simultaneously → one valid payload dir, no errors.
- `<javaExe> -cp <classpathB2R> com.glencoesoftware.bioformats2raw.Converter --version` succeeds; same for R2O.
- `<javaExe> -version` reports the pinned JRE version; no `java` on `PATH` and no `JAVA_HOME` are required.
