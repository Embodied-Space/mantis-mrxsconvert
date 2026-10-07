# 10 — Release

**Depends on:** all. **Decisions:** A3, A5.

**Status:** Implemented (2026-10-07). A clean clone builds and passes tests with `gradlew.bat build` (the
`reference/` tests skip). Still to confirm: the first CI run on GitHub, a tagged release, and a fresh QuPath install.

## Scope

- Gradle build based on `qupath/qupath-extension-template` at commit `bd825fc1b6d8ff25660e2fe853e97830b298986a`
  (2026-04-23; the repo has no tags). Pinned versions, verified on 2026-10-07 by building the template with JDK 25.0.4.1:
  - `settings.gradle.kts`: `qupath { version = "0.7.0" }`, plugin `io.github.qupath.qupath-extension-settings` **0.2.1**
    (repos: Gradle Plugin Portal + `https://maven.scijava.org/content/repositories/releases`).
  - `build.gradle.kts`: `id("qupath-conventions")`, `com.gradleup.shadow` 8.3.5, `qupathExtension { … }` block.
    Drop the template's `groovy` plugin and Groovy deps (we write Java).
  - Gradle wrapper **9.4.0**. The template's wrapper has no `distributionSha256Sum`. Add it (E2: pinned + verified downloads).
  - JDK **25** (QuPath 0.7 jars are class version 69).
- Build also fetches the pinned JRE (02) by URL + SHA-256, like the tools. *(Done with spec 02: pins and tasks are
  listed there. The installable jar is built by the `extensionJar` task, not `shadowJar`.)*
- Build pipeline: assemble payload (02) from pinned tool versions → extension jar.
  Tool distributions are fetched by the build from their pinned GitHub release URLs + checksums,
  **not** committed (`reference/` is local-only and git-ignored).
- GitHub Actions on Windows: build, unit tests (04–07), produce jar; on tag → GitHub Release with the jar.
  *(Done: `.github/workflows/build.yml`. Actions are pinned by commit SHA. The `payload-downloads` cache is keyed on
  `build.gradle.kts`. A tag `v<version>` builds with `-PreleaseVersion=<version>` (otherwise `0.1.0-SNAPSHOT`); the
  `release` job runs `gh release create` with `.github/release-notes.md`. A tag with `-` is a pre-release.)*
- License: GPL-3.0 `LICENSE`; payload `LICENSES/` includes bioformats2raw / raw2ometiff licenses, the JRE's (GPLv2 + CE) + third-party notices;
  release notes link to the tools' source. *(Done: root `LICENSE`, which the QuPath conventions plugin copies to
  `licenses/LICENSE` in the jar. The payload's `LICENSES/NOTICE.txt` points to `jre/legal/`.)*
- User README: install (drag jar onto QuPath), supported QuPath version, Windows-only, what it does/doesn't do,
  where logs and cache live, how to uninstall (delete jar + `%LOCALAPPDATA%\mantis-mrxsconvert`).

## Out of scope

QuPath extension catalog listing (user chose GitHub-only).

## Acceptance

- Clean checkout → `gradlew build` produces the jar on a Windows runner.
- Tagged commit produces a GitHub Release with the jar attached.
- Fresh QuPath 0.7 install + jar → convert a sample end-to-end without any other setup.
