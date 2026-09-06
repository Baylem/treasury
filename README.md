# Treasury

A personal finance calendar for income, expenses, subscriptions, installment
purchases, and loans. Manual entry only: Treasury never connects to a bank or
financial aggregator.

## What is implemented

- Shared Compose UI for desktop, Android, and iOS, with a beta browser target.
- Day, week, month, and pay-period calendars; per-account balances and search.
- Daily, weekly, monthly, and yearly recurrence with count/date endings,
  month-end clipping, and individual occurrence changes or skips.
- BNPL, equal installments, and fixed-rate amortized loans with exact cent
  allocation, repayment previews, and configurable payment intervals.
- Daily cash-flow forecasts, projected shortfalls, income/expense summaries,
  retained-income ratios, and category breakdowns.
- SQLDelight persistence on desktop/mobile; owner-scoped localStorage in the
  browser beta. JSON backup export/import, deletion tombstones, and separate
  permanent erasure.
- Optional server connection, email/password and configured Google/GitHub/Discord
  sign-in, manual sync, email verification, password recovery, and account deletion.
  The backend uses Ktor, PostgreSQL, Exposed, Flyway, and Argon2id.

The local profile works without an account or server. Connected profiles are
isolated by owner and server; use **Sync now** to exchange changes. Sessions stay
in memory, so restarting requires signing in again. Browser data can be removed
by site-data cleanup; keep backups.

Amounts use integer minor units throughout the financial engine. The UI supports
USD, EUR, GBP, CAD, and AUD with two decimal places and forecasts each currency
separately. Scheduled payments are projections, not confirmation of settlement.
See [calculation conventions](docs/engine.md).

## Run locally

Install **Eclipse Temurin JDK 21**. Use the checked-in Gradle wrapper. Android also
requires SDK 36; iOS requires macOS and Xcode. On Windows, replace `./gradlew`
with `.\gradlew.bat`.

| Target | Command |
| --- | --- |
| Desktop | `./gradlew :composeApp:run` |
| Android debug APK | `./gradlew :app:androidApp:assembleDebug` |
| WebAssembly development server | `./gradlew :app:webApp:wasmJsBrowserDevelopmentRun` |
| JavaScript development server | `./gradlew :app:webApp:jsBrowserDevelopmentRun` |
| Backend | `./gradlew :server:run` after [server configuration](docs/backend.md) |

For iOS, open `app/iosApp/iosApp.xcodeproj` in Xcode. The entry points share the
same `composeApp` UI and `shared` engine.

On first launch, create an account with a balance **before entries on its balance
date**, then add income, bills, subscriptions, or plans. Use Settings for backup,
pay-period preferences, and optional server connection.

Desktop data is stored in `Treasury/treasury.db` under Windows `LOCALAPPDATA`,
macOS `~/Library/Application Support`, or Linux `XDG_DATA_HOME` (default
`~/.local/share/treasury/treasury.db`). The JVM system property
`treasury.dataDirectory` overrides the containing directory for isolated testing.
Local databases and JSON exports rely on OS/storage access controls and disk
encryption; the application does not encrypt them itself.
See [storage, backup, and sync behavior](docs/local-storage-and-sync.md) for profile
isolation, conflict handling, and browser storage limitations.

## Layout

```text
shared/             Pure domain/engine, repository interfaces, sync client
composeApp/         Shared UI, controller, platform persistence and DI
app/androidApp/     Android entry point and resources
app/desktopApp/     Desktop entry point and native packaging
app/iosApp/         Xcode project and iOS entry point
app/webApp/         Wasm/JS entry point and branded web resources
server/             Ktor API, PostgreSQL storage, migrations, deployment
docs/               Engine conventions, UI verification, release requirements
```

The pure engine has no I/O. UI/domain code depend on repository interfaces.
Recurrences store rules and exceptions, never pre-generated occurrence rows.
Every persisted entity carries owner-scoped sync metadata. See [AGENTS.md](AGENTS.md)
for architecture invariants.

Launcher branding is maintained as SVG and Android vector sources. Generated iOS
and desktop icons are checked in; see [icon generation](scripts/README.md) to
update them without changing the ordinary application build.

## Verify

```shell
./gradlew :shared:jvmTest :composeApp:jvmTest :server:test
./gradlew :shared:compileKotlinWasmJs :shared:compileTestKotlinWasmJs :composeApp:compileKotlinWasmJs
```

On macOS, also run `./gradlew :shared:iosSimulatorArm64Test` and build both iOS
frameworks. `./gradlew :shared:allTests` requires the toolchains and browser support
for the enabled targets.

Tests cover engine edge cases, monetary overflow, repository conflicts and
durability, sync, server routes, application lifecycle, and real Compose form
flows. UI tests render desktop/phone-width PNGs under `composeApp/build/qa`;
[UI verification notes](docs/ui-verification.md) explain their scope. Server tests
default to an isolated H2 compatibility database; the [backend guide](docs/backend.md)
documents running the same tests against real PostgreSQL.

[CI](.github/workflows/ci.yml) defines Windows/Linux JVM checks, PostgreSQL
integration, Wasm/JS compilation, and macOS native checks. A configured workflow
does not establish that its remote jobs have run.
See the [local verification record](docs/verification.md) for completed checks and
the generated installation artifacts.

## Before release

This repository is not a deployed service or a signed store release. Production
requires deployment secrets, TLS, backups and monitoring, real OAuth/SMTP setup,
native device testing, accessibility review, and platform signing/notarization.
Web remains beta; platform-specific behavior needs verification on actual devices.

Use the [release checklist](docs/release-checklist.md) and
[backend deployment guide](docs/backend.md) before distributing to users.
