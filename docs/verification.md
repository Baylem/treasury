# Local verification — 2026-09-06

These checks were run against the working tree on Windows with Temurin JDK 21.
They establish the results below, not a signed release, a remote CI run, or an
assessment of a live production service.

## Automated suites

| Suite | Passed | Failed / skipped |
| --- | ---: | ---: |
| Shared engine, repository, and sync client — JVM | 86 | 0 / 0 |
| Compose UI, controller, and SQLite — JVM | 37 | 0 / 0 |
| Controller and browser storage — Chrome JavaScript | 23 | 0 / 0 |
| Controller and browser storage — Chrome WebAssembly | 23 | 0 / 0 |
| Server routes, security, persistence, and client integration — H2 | 24 | 0 / 0 |
| **Total target-specific test executions** | **193** | **0 / 0** |

The same 24 backend tests also passed against PostgreSQL 18.6. Browser suites
exercise native Web Locks, including concurrent writers and cancellation; they
do not establish visual or accessibility coverage of the browser UI.

The JVM UI suites exercise real Compose forms, calendar navigation, recurrence
edits, BNPL previews, deletion, and app startup/reopening through Koin and SQLite.
Desktop and phone-width screenshots were reviewed; see [UI verification](ui-verification.md).
They do not substitute for Android or iOS device tests.

## Builds and runtime checks

- Android debug and unsigned release APKs built; both variants passed Android lint
  without errors. Dependency update notices remain; pinned dependency versions
  were retained for the verified build. Release lint also reports monochrome-icon
  warnings for the API 26 resources; API 33 resources supply the monochrome layer.
  Android backup/transfer exclusions use explicit data extraction rules. The
  merged release manifest disables cleartext traffic and automatic backup.
- Windows distributable and unsigned MSI built. The bundled executable's
  `--verify-installation` check exited successfully, using its own Java runtime and
  native SQLite against an isolated workspace database.
- JavaScript and WebAssembly sources compiled. The optimized Wasm distribution
  built, and the generated page and assets were served successfully over HTTP.
- The Linux backend image built and passed readiness, real Argon2id registration,
  sync upload/download, account erasure, and session-revocation checks.
- The Docker Compose stack passed registration/login with the non-superuser
  application database role. Only task-owned containers, networks, volumes, and
  temporary credentials were removed after verification.

## Reproduce and locate artifacts

Use the Gradle wrapper with JDK 21. On Windows use `gradlew.bat`.

```shell
./gradlew :shared:jvmTest :composeApp:jvmTest :server:test
./gradlew :composeApp:jsBrowserTest :composeApp:wasmJsBrowserTest
./gradlew :app:androidApp:assembleDebug :app:androidApp:lintDebug
./gradlew :app:androidApp:assembleRelease :app:androidApp:lintRelease
./gradlew :app:desktopApp:createDistributable :app:desktopApp:packageMsi
./gradlew :app:webApp:wasmJsBrowserDistribution :server:installDist
```

Set `CHROME_BIN` if headless Chrome is not discovered automatically. See the
[backend guide](backend.md) for PostgreSQL test environment variables.
Native installer tasks require their respective operating system.

| Output | Project-relative path |
| --- | --- |
| Windows MSI | `app/desktopApp/build/compose/binaries/main/msi/Treasury-1.0.0.msi` |
| Bundled Windows launcher | `app/desktopApp/build/compose/binaries/main/app/Treasury/Treasury.exe` |
| Android debug APK | `app/androidApp/build/outputs/apk/debug/androidApp-debug.apk` |
| Android unsigned release APK | `app/androidApp/build/outputs/apk/release/androidApp-release-unsigned.apk` |
| Wasm site | `app/webApp/build/dist/wasmJs/productionExecutable/` |
| Server distribution | `server/build/install/server/` |
| UI screenshots | `composeApp/build/qa/` |

Build artifacts and test reports are generated files and are not committed.
When using the installation diagnostic, set `treasury.dataDirectory` as a JVM
system property to an isolated directory; otherwise it opens the normal profile.

## Remaining release gates

Signing/notarization, real Android/iOS device testing, macOS/Linux desktop packages,
remote CI, accessibility review, live OAuth/SMTP setup, TLS deployment, operational
monitoring, and backup/restore drills remain. These are listed in the
[release checklist](release-checklist.md). Web remains beta, sessions remain in
memory, sync is manual, and local financial files rely on OS access controls and
disk encryption. No public deployment or store submission was performed.
