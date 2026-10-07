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
    LICENSES/       # tool LICENSE.txt files + NOTICE.txt (JRE notices stay in jre/legal/)
  ```
  Dedup is by **byte equality**, verified at build time (fail build if a "common" jar differs).
- **JRE (A4, revised):** QuPath 0.7.0's runtime has no `java.exe` (spike 01 Q3), so we ship our own.
  Confirmed (user, 2026-10-07): Eclipse Temurin **JRE 25** Windows x64 zip (the tools were verified on Java 25.0.2), pinned by version +
  SHA-256 like the tools (E2). Licence GPLv2 + Classpath Exception; notice goes in `LICENSES/`.
  Must include at least the modules spike 01 proved sufficient: `java.se`, `jdk.unsupported`, `jdk.zipfs`.
- Payload stored as resources inside the extension jar (not as loose jars QuPath could load — see 01 Q4).
  **Never via the Shadow plugin:** Shadow unpacks every `.jar` it is given, which merged the tools' classes into the
  extension jar (35 550 entries) — i.e. onto QuPath's classpath, violating A4. A plain `Jar` task adds them instead.
- **Runtime:** `ToolPayload.ensureExtracted() -> ToolPaths`
  - Target: `%LOCALAPPDATA%\mantis-mrxsconvert\tools\<payload-hash>\`.
  - Extract to a sibling temp dir, verify SHA-256s, atomic rename into place. Concurrent QuPath instances must not corrupt it.
    The rename retries for up to **60 s** while access is denied (virus scanners and file watchers hold fresh files
    open; a 5 s window was seen to fail with VS Code watching the folder). *(Extended 2026-10-07, user.)*
  - If target exists and a `.complete` marker is present, reuse without re-hashing.
  - Old `<payload-hash>` dirs may be deleted when not in use (best-effort).
- `ToolPaths` exposes: `javaExe` (`jre\bin\java.exe`), `classpathB2R` (`common\*;b2r\*`), `classpathR2O` (`common\*;r2o\*`), `logbackConfigB2R`, `logbackConfigR2O`.

## Implementation (done 2026-10-07)

**Pins** (`build.gradle.kts`, E2). Each download is fetched once into `.gradle/payload-downloads/` (survives `clean`)
and SHA-256-verified:

| Artifact | URL (GitHub release asset) | SHA-256 |
|----------|----------------------------|---------|
| bioformats2raw 0.12.1 | `glencoesoftware/bioformats2raw` `v0.12.1/bioformats2raw-0.12.1.zip` | `51fbbf04a83c2042b707fce016ad0c8260d37194ff8fe7d986d53f4ebee116a6` |
| raw2ometiff 0.10.1 | `glencoesoftware/raw2ometiff` `v0.10.1/raw2ometiff-0.10.1.zip` | `e78a22e60ee44eb462abecfc60b997f67c5ed399486a962a654fb30d9b54d9b8` |
| Temurin JRE **25.0.4.1+1** | `adoptium/temurin25-binaries` `jdk-25.0.4.1+1/OpenJDK25U-jre_x64_windows_hotspot_25.0.4.1_1.zip` | `4c95451cea98556def2c54f7782933f52a26d4a36bd85e1d59f0364464828b07` |

The two tool zips are byte-identical to `reference/`. The full Temurin JRE is used (no jlink): 188 MB unpacked.

**Build tasks:** `downloadBioformats2raw`, `downloadRaw2ometiff`, `downloadJre` → `assemblePayload` (`build/payload/`,
fails if a shared-name jar differs or a tool's own-jar set drifts from the lists above) → `shadowJar` (code only,
`-code` classifier) → `extensionJar` (the installable `-all` jar: code + payload under
`qupath/ext/mantis/mrxsconvert/payload/`). `NOTICE.txt` comes from `src/payload/`.

**manifest.json:** `{id, versions{bioformats2raw, raw2ometiff, jre}, files[{path, size, sha256}]}`. `id` = first
16 hex chars of SHA-256 over the sorted `path<TAB>sha256<LF>` lines; it names the extraction dir.

**Runtime** (`qupath.ext.mantis.mrxsconvert.payload`): `ToolPayload.ensureExtracted()` (blocking; keep off the
FX thread) → `ToolPaths(root, javaExe, classpathB2R, classpathR2O, logbackConfigB2R, logbackConfigR2O)`.
Under `tools\`: `<id>\` (with `.complete`), `<id>.extracting\` (staging), `<id>.lock` (exclusive, held while
extracting), `<id>.inuse` (shared, held by each QuPath instance for its lifetime). Other versions' dirs are deleted
only when their `.inuse` can be locked exclusively; lock files are never deleted (deleting them could let two
instances lock different files of one name). Hash mismatch → `IOException` ("extension jar is damaged"), staging
removed. A dir without `.complete` is deleted and re-extracted.

**Sizes:** `-all` jar 266 MB (553 entries); extracted payload 385 MB; extraction ≈ 5 s.

**Quirk:** `bioformats2raw --version` exits non-zero (127 on Java 25) after printing; `raw2ometiff --version` exits 0.
Tests check the printed version, not the exit code.

## Out of scope

Running the tools (03). Downloading anything. Non-Windows paths.

## Acceptance

Status 2026-10-07: all met by `gradlew build` (tests in `src/test/java/…/payload/`), except where noted.

- Built extension jar < ~280 MB (tools ≈ 205 MB deduplicated + JRE ≈ 58 MB zipped). ✅ 266 MB.
- Fresh machine: first call extracts and returns valid paths; second call is instant. ✅ (second call reads nothing).
- Corrupting one extracted jar → next call detects (on missing marker) or re-extract path works. ✅ (marker removed → re-extracted).
- Two QuPath instances calling simultaneously → one valid payload dir, no errors. ✅ (4 JVMs at once).
- `<javaExe> -cp <classpathB2R> com.glencoesoftware.bioformats2raw.Converter --version` prints its version; same for R2O. ✅
- `<javaExe> -version` reports the pinned JRE version; no `java` on `PATH` and no `JAVA_HOME` are required. ✅
- No tool class is loadable from the extension jar itself (A4). ✅
- QuPath 0.7 installs and loads the 266 MB jar. ✅ (user, 2026-10-07: drag-and-drop install, menu item, Manage
  extensions entry, clean log).
- Not yet checked: extraction from inside QuPath (its class loader, `%LOCALAPPDATA%`). First exercised when spec 03/08
  call `ensureExtracted()`.
