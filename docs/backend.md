# Treasury backend

The Ktor service stores accounts, sessions and owner-scoped financial records in PostgreSQL. It depends on `:shared` and uses the same serialized domain model, validation and deterministic LWW merge as the clients. It does not connect to banks. Monetary values remain integer minor units throughout.

## Run locally

Use JDK 21. Create a PostgreSQL database and a dedicated database role that can create tables in its own schema. Set these environment variables before `./gradlew :server:run`:

| Variable | Meaning |
| --- | --- |
| `DATABASE_URL` | JDBC PostgreSQL URL, for example `jdbc:postgresql://localhost:5432/treasury` |
| `DATABASE_USER`, `DATABASE_PASSWORD` | Database credentials; no default password |
| `PUBLIC_URL` | Exact public origin, without a path or trailing slash |
| `TREASURY_ENV` | Use `development` only for a loopback HTTP origin |
| `PORT` | Listener port, default 8080 |
| `CORS_ORIGINS` | Comma-separated exact browser origins; empty disables cross-origin browser access |
| `TRUSTED_PROXY_ADDRESSES` | Comma-separated literal IPs of trusted reverse proxies; empty ignores forwarding headers |

For local development, use `PUBLIC_URL=http://localhost:8080` and `TREASURY_ENV=development`. The API binds to all interfaces, so limit exposure with a firewall or a loopback container port. Production configuration requires an HTTPS public origin. Terminate TLS at a reverse proxy; do not expose the plain HTTP application listener to the Internet. Remote database connections should use certificate verification, for example `sslmode=verify-full` and a trusted root certificate.

Flyway validates and applies migrations on startup. Migration failure stops startup. `GET /health/live` checks the process; `GET /health/ready` checks the database. A readiness failure returns 503 without database details. The runtime uses Exposed JDBC transactions on the IO dispatcher and a bounded Hikari pool.

## Package and deploy

1. Run `./gradlew :shared:jvmTest :server:test :server:installDist`.
2. Copy `server/deploy/.env.example` to `server/deploy/.env` and fill in unique credentials, HTTPS origin and optional providers/email settings.
3. Run `docker compose -f server/deploy/compose.yaml --env-file server/deploy/.env up --build -d`.
4. Configure an HTTPS reverse proxy to the loopback API port. Add per-client edge rate limits and request timeouts, with a 1 MiB request limit.

The image runs as an unprivileged user with a read-only filesystem and temporary native-library storage. The bounded `/tmp` tmpfs explicitly permits executable mappings because JNA extracts the Argon2 native library there; a `noexec` mount prevents password authentication. Compose creates a separate non-superuser application database role. The PostgreSQL 18 volume uses `/var/lib/postgresql`, matching the [official image layout](https://hub.docker.com/_/postgres). Initialization runs only for a new data volume. Existing credentials must be rotated using PostgreSQL, not merely by changing environment variables. Pin image digests in release infrastructure and scan dependencies/images before shipping.

Database volumes and backups need encryption at rest and restricted host access. There is no end-to-end encryption: the server can process financial records. Back up PostgreSQL with `pg_dump` or managed point-in-time recovery, encrypt backups, and periodically restore into an isolated environment. Establish retention and erasure policies for backups before accepting real financial data. Never log request bodies, authorization headers, OAuth callback query strings, or SMTP content at the proxy.

## Authentication

All JSON endpoints use `application/json`. Errors have `{code,message,requestId}`. Registration requires a syntactically valid email and a password of 12–128 characters, with a 512-byte maximum. Passwords use Argon2id with 64 MiB, three iterations and one lane; two concurrent hashing operations are allowed per process. Session tokens contain 256 random bits; only SHA-256 token hashes are stored. Sessions expire after 30 days. At most five sessions remain active per user.

| Endpoint | Request / response |
| --- | --- |
| `POST /v1/auth/register` | `{email,password}` → 201 `{token,ownerId,expiresAt}` |
| `POST /v1/auth/login` | `{email,password}` → 200 session |
| `GET /v1/auth/me` | Bearer token → `{ownerId,email}` |
| `POST /v1/auth/reauthenticate` | Bearer + `{password}` → fresh session |
| `POST /v1/auth/logout` | Bearer → 204; revokes this session |
| `POST /v1/auth/logout-all` | Bearer → 204; revokes all owner sessions |

Send `Authorization: Bearer <token>` for native clients. Credentials are separate from financial snapshots and backups. The browser OAuth flow uses secure, HttpOnly, host-only, SameSite=Lax cookies in production. Cookie-authenticated writes additionally require `X-Treasury-CSRF: 1` and an allowed `Origin`. Bearer authentication is preferred when a native client is available.

The service limits authentication to 15 requests per minute per client IP and adds email-keyed login/recovery limits. Other requests are bounded per IP and owner. Forwarding headers are ignored unless the direct peer is listed in `TRUSTED_PROXY_ADDRESSES`; the chain is processed from right to left and stops at the first untrusted peer. Configure only the actual proxy addresses, and ensure the proxy appends the observed client address. Without this configuration, an upstream proxy's IP shares the application bucket. Limiter state is bounded and expires, but is process-local. Multi-replica deployment requires a shared edge limit. Request bodies are capped at 8 KiB, except sync at 1 MiB.

## Email verification and recovery

Configure `SMTP_HOST`, `SMTP_PORT` (default 587), `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM`, and `SMTP_TLS` (`starttls` or `tls`). TLS and hostname verification are required. Without SMTP configuration, requests for email codes return `503 email_unavailable`; ordinary local use and sign-in still work.

| Endpoint | Request / response |
| --- | --- |
| `GET /v1/auth/email-status` | Bearer → `{deliveryAvailable,verified}` |
| `POST /v1/auth/email-verification/request` | Bearer → 202 |
| `POST /v1/auth/email-verification/complete` | `{token}` → 204 |
| `POST /v1/auth/password-reset/request` | `{email}` → generic 202 whether the account exists or not |
| `POST /v1/auth/password-reset/complete` | `{token,password}` → 204; revokes every session |

Codes contain 256 random bits, expire after 30 minutes, and can be used once. The database stores a hash for validation. Email delivery uses a transactional outbox with a three-minute retry lease and up to six attempts. Unsent email bodies temporarily contain the code and are removed on delivery, replacement, expiry or erasure; protect database and backup access accordingly. Verification is available but does not gate personal financial sync. Email accounts are not automatically linked to OAuth identities.

## OAuth

Set both `<PROVIDER>_CLIENT_ID` and `<PROVIDER>_CLIENT_SECRET` for `GOOGLE`, `GITHUB`, and/or `DISCORD`. Register exactly `PUBLIC_URL/v1/auth/oauth/<provider>/callback`. Disabled providers are absent from `GET /v1/auth/providers`. Minimal scopes are Google `openid`, GitHub an empty scope, and Discord `identify`. Only the stable provider subject is retained; provider access tokens are discarded. State is cookie-bound, persisted with a hash, expiring and one-use. Google and GitHub use S256 PKCE; Discord uses its documented confidential-client code flow with state and client authentication.

Native apps use the device bridge, avoiding bearer tokens in browser URLs:

1. `POST /v1/auth/oauth/device` with `{provider}` returns `{attemptId,pollSecret,authorizationUrl,verificationCode,expiresAt}`.
2. Display the eight-digit verification code in the app and open `authorizationUrl` in the browser.
3. After provider login, the browser asks the user to enter the code displayed by their app. A separate HttpOnly cookie and form nonce bind this approval to the browser. The browser never reveals the app's code.
4. Poll `POST /v1/auth/oauth/device/poll` with `{attemptId,pollSecret}`, at least three seconds apart. The response is 202 `{status:"pending"}`, then 200 session on approval. The attempt expires after ten minutes and can be redeemed once; expired/consumed attempts return 410. Five incorrect code attempts invalidate approval.

The simpler browser-only `/v1/auth/oauth/<provider>/start` flow sets a browser session cookie after callback. Provider registrations, consent-screen verification and live provider callback tests require deployment-specific credentials. The test suite exercises the entire callback/device flow with a deterministic provider substitute, not live provider credentials.

## Sync protocol

`POST /v1/sync` accepts a `TreasurySnapshot` containing up to 500 entities. Metadata is nested as `meta: {id,ownerId,createdAt,updatedAt,deletedAt,revision}`. Entity IDs must be canonical lowercase UUIDs; ownership comes from the session. A batch is validated and committed atomically. Creation time and entity type are immutable. Timestamps more than five minutes ahead of server time are rejected. Each account is limited to 50,000 records and 16 MiB of serialized record data, including tombstones.

LWW ordering is shared with clients: `updatedAt`, then `revision`, then deletion precedence, then canonical serialized content. The response is `{snapshot}` containing authoritative versions of every submitted ID, including changes that lost conflict resolution. The shared client sends parent records before dependents when splitting uploads.

`GET /v1/sync?cursor=<ISO timestamp>&limit=500` returns `{snapshot,cursor,hasMore}`. Omit the cursor for the first pull. The cursor is a per-owner monotonically increasing **server acceptance timestamp**, separate from each entity's client-authored LWW `updatedAt`. This distinction prevents losing newly uploaded offline records with old client timestamps. Each changed record receives a distinct server timestamp under an owner lock, so pages have no timestamp ties. Clients must follow the returned cursor, gather pages before validating cross-record references, and advance their durable cursor only after a successful merge. Restarting at the beginning is safe.

Occurrences are never persisted: entries retain recurrence rules and only user changes create `OccurrenceOverride` records. Regular sync never hard-deletes financial rows. Tombstones are returned to other devices and retained.

## Export and erasure

`GET /v1/account/export` returns an authenticated owner's complete snapshot, including tombstones. `DELETE /v1/account` requires a session created within the last ten minutes and `{confirmation:"DELETE_MY_ACCOUNT"}`. Password users can obtain a fresh session through reauthentication; OAuth users sign in again. Erasure revokes sessions and atomically deletes the account, provider identities, financial records, tombstones, pending challenges and mail. It is separate from normal sync deletion.

Erased owner IDs cannot be recreated with stale credentials. A later registration gets a new owner ID. Offline device copies cannot be remotely wiped while disconnected: they must be erased locally; obsolete sessions fail when reconnecting. Backup retention and any deployment-level data copies must be handled by the operator's erasure procedure.

## Verification

Run `./gradlew :server:test` for HTTP/security tests and production migrations against H2 in PostgreSQL mode. H2 2.3.232 is intentional because [2.4.240 has a CHECK-constraint session regression](https://github.com/h2database/h2database/issues/4291).

To run the same suite against real PostgreSQL, set `TREASURY_TEST_DATABASE_URL`, `TREASURY_TEST_DATABASE_USER`, and `TREASURY_TEST_DATABASE_PASSWORD`. Use a **disposable test database**: every test creates a separate `treasury_test_<uuid>` schema. The suite covers migrations, concurrent writes, owner injection, late offline paging, tombstones, session expiry/revocation, password reset, email outbox, CSRF, body/rate limits, OAuth one-use state/device handoff and the shipping shared client synchronizing two local repositories through the real HTTP API.

Verified on 2026-09-06: all 24 backend tests passed on H2 and PostgreSQL 18.6. The Linux production image built and passed readiness, real Argon2id registration, sync push/pull and erasure/revocation checks. The Compose stack also passed registration/login using the non-superuser application role. Live OAuth provider credentials, live SMTP delivery, public TLS and operational backup/restore procedures remain deployment checks.
