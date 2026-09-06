# Treasury

A personal finance calendar for mobile, desktop, and web: income, expenses,
loans, buy-now-pay-later plans, and recurring subscriptions across
day / week / month / pay-period views, with cash-flow forecasting and
financial-health insights. **Manual entry only — no bank/aggregator integration.**

## Stack

- **Language:** Kotlin (Kotlin Multiplatform)
- **UI:** Compose Multiplatform — one shared UI for Android, iOS, and Desktop. Web (Wasm) is a beta target, polished last.
- **Local store:** SQLDelight
- **Networking:** Ktor client + kotlinx.serialization
- **Backend:** Ktor server (depends on `:shared`) + Postgres; data access via Exposed; migrations via Flyway
- **Auth:** email/password + OAuth (Google, GitHub, Discord); Argon2id for password hashing
- **DI:** Koin
- **Build:** Gradle (Kotlin DSL) · **JDK:** 21 (Temurin) · **Group:** `dev.baylem`

## Architecture — invariants, do not violate

- **The shared engine is the core.** All domain logic — recurrence expansion,
  installment/loan math, forecasting, pay-period derivation — lives in
  `shared/commonMain` as pure, I/O-free functions and compiles into every client
  **and** the server. One implementation; never duplicate it per platform.
- **Repository seam.** Domain and UI depend only on the `Repository` / `DataStore`
  interfaces, never on SQLDelight or Ktor directly. Local now (SQLDelight); sync
  later, behind the same interface.
- **Local-first, sync-ready.** Every persisted entity implements `SyncMeta`:
  client-generated UUID `id` (prefer v7), `ownerId`, `createdAt`, `updatedAt`,
  `deletedAt`, `revision`. Sync is delta-by-`updatedAt`, last-write-wins.
- **Recurrence = rule + exceptions.** Store the `RecurrenceRule`; compute
  occurrences virtually for a date window; persist only `OccurrenceOverride` rows
  for instances the user changed. Never pre-generate occurrence rows.

## Conventions — gotchas

- **Money is `Long`, integer minor units (cents). Never `Double`/float.**
- **Dates:** `LocalDate` for calendar days ("due on the 1st"); `Instant` for sync
  timestamps only. Never use a raw timestamp for a scheduled financial event.
- **Model unions are sealed** (`RecurrenceEnd`, `OverrideAction`,
  `OccurrenceSource`) — handle with exhaustive `when`, no `else` branch.
- **Never hard-delete in the sync path.** `softDelete` sets `deletedAt` (tombstone).
  A separate hard-purge path exists ONLY for GDPR erasure — it removes the row and
  its tombstone after propagation. Soft-delete ≠ erasure; keep the two distinct.
- Scope every row to `ownerId` from day one (stubbed locally until auth lands).
- Encryption is **standard-strong** (TLS + at-rest + access control), **not**
  end-to-end. Request minimal OAuth scopes.
- In `commonTest`, use **camelCase test names** — backtick names with spaces are a
  JVM-only nicety and break on the iOS/Wasm targets.

## Layout

```
shared/       commonMain: domain model, engine, repository interfaces (the core)
composeApp/   Compose Multiplatform UI (Android, iOS, Desktop, Web)
iosApp/       iOS entry point
server/       Ktor server (depends on :shared)
```

## Build & test

- Run desktop app: `./gradlew :composeApp:run`
- Fast engine tests (JVM): `./gradlew :shared:jvmTest`
- All shared tests (all targets): `./gradlew :shared:allTests`
- Run server: `./gradlew :server:run`

## Current status

Engine is being built **test-first** in `shared`, starting with recurrence
expansion in `DefaultCalendarEngine.expand()`. Work order:
`expand` → `schedulePlan` (BNPL / installment / amortized) → `forecast` →
`payPeriods` → `health`. Persistence (SQLDelight) and the calendar UI come after
the engine is green end-to-end.