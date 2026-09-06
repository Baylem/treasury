# Local storage, profiles, and sync

Treasury works without an account or a network connection. Financial entry is manual;
there are no bank connectors. The UI uses `Repository` and the account controller;
SQLDelight and Ktor stay behind those interfaces.

## Durable profiles

Android, iOS, and desktop use the generated SQLDelight database in
`composeApp/src/commonMain/sqldelight`. Each row stores a typed, serialized domain
entity plus indexed sync metadata. Recurring occurrences are computed by the engine;
only explicit occurrence overrides are stored.

The guest profile is `local`. Signed-in profiles are partitioned by **both** the
canonical server address and authenticated owner ID. The physical SQL partition key
is the collision-free JSON encoding `[server, ownerId]`; the entity's `meta.ownerId`
remains the actual owner ID expected by the server. This prevents a second server
that returns an identical owner UUID from reading or uploading the first server's
cached data. Host case, default ports, and trailing path slashes are normalized.

Desktop defaults:

| Platform | Database directory |
| --- | --- |
| Windows | `%LOCALAPPDATA%/Treasury` |
| macOS | `~/Library/Application Support/Treasury` |
| Linux | `$XDG_DATA_HOME/treasury`, or `~/.local/share/treasury` |

The JVM property `treasury.dataDirectory` overrides the desktop directory, including
for isolated tests. Desktop SQLite uses WAL, full synchronization, and a five-second
busy timeout. Android uses its private application database directory; iOS uses the
native SQLDelight driver and application sandbox.

Android disables automatic cloud backups and explicitly excludes app storage from
device transfers using the platform's
[data extraction rules](https://developer.android.com/identity/data/autobackup).
Use Treasury's explicit JSON backup or optional sync to move financial profiles.

The browser beta uses one atomic `localStorage` snapshot per scoped profile, with
exclusive Web Locks covering each compare/write or purge across tabs. Cancellation
releases held locks or aborts queued lock requests. Browsers without Web Locks or a
secure context show a save error instead of writing unsafely. It reports access and
quota failures. Browser storage can be removed by site-data
clearing, private browsing, or browser policies; export backups for data you need to
retain. Browser persistence is not SQLDelight's Web Worker driver.

Financial payloads and exported JSON are not encrypted by Treasury itself. Production
devices must use OS disk/device encryption and appropriate account access controls.
Server deployment requires TLS and encrypted database/storage volumes. This is not
end-to-end encryption. Passwords, bearer tokens, and OAuth polling secrets are kept
in memory and never included in financial rows, saved UI state, or backups.

## Safe writes and recovery

Opening storage happens during `Repository.initialize`, not UI construction. An
unreadable or corrupt database produces `RepositoryState.Failure` and is not silently
replaced with an empty database. Initialization can be retried.

Mutations validate the complete owner-scoped reference graph, commit atomically,
then publish the new `StateFlow` snapshot. A failed write leaves the last committed
UI state intact. Revision and timestamp checks reject stale edit forms. Timestamps
advance monotonically even if the device clock moves backward.

Deleting an account or recurring entry tombstones its dependents in the same
transaction. Ordinary SQLite writes use insert-if-absent plus update, never SQLite
`REPLACE` or `DELETE`. Tombstones remain available for sync. SQLite compares the
previously loaded payloads inside its write transaction to detect another window's
changes; browser storage also checks for external changes before writing.

Backups are strict, versioned JSON (`treasury-backup`, version 1). Imports validate
the owner, metadata, canonical UUIDs, and relationships before one atomic merge. They reject
unsupported formats and inputs over 20 million characters. Import preserves existing
records and newer tombstones. Keep backups private: they contain financial details.

Signing in uses a separate durable profile. Copying guest data into that account is
an explicit, unchecked-by-default UI option. Copying preserves stable UUIDs so repeating
it does not duplicate records. It leaves the original guest profile intact. Signing
out switches back to the guest profile and closes the signed-in repository.

## Synchronization

Sync is opt-in and initiated with **Sync now**. Authentication supports email/password
and configured Google, GitHub, or Discord providers. Native OAuth uses a browser
approval flow with an eight-digit code shown by the app; polling secrets never enter
URLs or UI state. Password reset and email verification require configured server
email delivery. Session credentials remain in memory, so app restart requires sign-in.

The synchronizer downloads and accumulates all delta pages before committing them,
because a page may reference parents in another page. It then uploads changed records
with parents before dependents. Batches contain at most 500 entities and stay below
900 KB of UTF-8 JSON. Retries are idempotent. Writes made during a network request
remain local and are reported as pending when another sync is needed.

Conflict resolution is deterministic: latest `updatedAt`, then highest revision,
then deletion precedence, then canonical serialized payload order. A record's
creation timestamp and type cannot change. The server's delta cursor is separate
from client timestamps, so newly uploaded backdated offline edits are not skipped.
Cursors are held in memory; restarting safely pulls from the beginning.

The transport requires HTTPS except for loopback development servers, does not follow
redirects, and uses request/connect/socket timeouts. Responses are streamed and capped
before JSON decoding: 20 MiB for success and 64 KiB for errors. Request bodies must be
smaller than 1 MiB. Tokens and request bodies are not logged.

Permanent erasure is a separate path. Remote account erasure requires recent
authentication and explicit `DELETE_MY_ACCOUNT` confirmation, invalidates sessions,
and removes remote financial rows and tombstones. Only after the server confirms does
the controller purge that profile on this device. Other devices' local copies and
user-created backups must be cleared separately.

## Regression coverage

Run `./gradlew :shared:jvmTest :composeApp:jvmTest` for the shared engine, repository,
sync transport, controller, SQLDelight, and Compose UI suites. Storage tests exercise
real SQLite reopening, owner/server isolation, stale-window conflicts, tombstone
retention, and rollback after a trigger aborts a transaction mid-write. Controller
tests cover account transitions, explicit guest copying, OAuth pending/approval/cancel,
failed authentication/storage, cancellation, recovery, and erasure ordering. Sync
tests cover interrupted pagination, lost acknowledgements, response size bounds, and
concurrent edits during download and upload.

`./gradlew :composeApp:jsBrowserTest :composeApp:wasmJsBrowserTest` runs the shared
controller/storage tests and real Web Locks integration tests in headless Chrome.
Set `CHROME_BIN` to your Chrome executable when it is not discovered automatically.
The browser tests prove that independent writers cannot enter the same critical
section together and that cancellation releases held locks and aborts queued requests.
