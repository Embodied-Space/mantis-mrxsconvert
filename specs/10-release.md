# 10 — Release

**Depends on:** all. **Decisions:** A3, A5.

## Scope

- Gradle build using the QuPath extension template/plugin version matching 01's findings.
- Build pipeline: assemble payload (02) from pinned tool versions → extension jar.
  Tool distributions are fetched by the build from their pinned GitHub release URLs + checksums,
  **not** committed (`reference/` is local-only and git-ignored).
- GitHub Actions on Windows: build, unit tests (04–07), produce jar; on tag → GitHub Release with the jar.
- License: GPL-3.0 `LICENSE`; payload `LICENSES/` includes bioformats2raw / raw2ometiff licenses + third-party notices;
  release notes link to the tools' source.
- User README: install (drag jar onto QuPath), supported QuPath version, Windows-only, what it does/doesn't do,
  where logs and cache live, how to uninstall (delete jar + `%LOCALAPPDATA%\mantis-slideconvert`).

## Out of scope

QuPath extension catalog listing (user chose GitHub-only).

## Acceptance

- Clean checkout → `gradlew build` produces the jar on a Windows runner.
- Tagged commit produces a GitHub Release with the jar attached.
- Fresh QuPath 0.7 install + jar → convert a sample end-to-end without any other setup.
