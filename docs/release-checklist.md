# Release readiness

The repository contains a working application, automated tests, CI configuration,
and deployment scaffolding. A source build is not a signed, deployed production
release. Complete and record the checks below for the exact commit being shipped.

## Builds and device validation

- [ ] Run the full CI workflow on Windows, Linux, and Apple Silicon macOS. Require
  shared engine/repository, Compose UI/storage/controller, real PostgreSQL, and
  native simulator tests to pass. Preserve reports and review UI screenshots.
- [ ] Build and install Android release artifacts with the production application
  ID, signing key, version code, and store configuration. Test Android 24 and a
  current device with screen rotation, process death, background/resume, large
  fonts, TalkBack, and the software keyboard. Verify adaptive/themed launcher icons.
- [ ] Build the iOS app in Xcode, configure the Apple team, bundle identifier,
  capabilities, signing, and provisioning; install on a real device. Test safe
  areas, keyboard dismissal, VoiceOver, Dynamic Type, lifecycle changes, and
  native SQLite persistence. Review the generated Treasury App Store icon on
  device and in store previews, including the system's dark/tinted treatment.
- [ ] Build the MSI, DMG, and DEB on their respective hosts. Configure Windows
  signing and Apple signing/notarization, verify install/update/uninstall behavior,
  package icons, filesystem permissions, and persistence after application restart.
- [ ] Test Wasm and JS distributions in the supported browser matrix. Verify
  startup/error states, keyboard and assistive technology access, localStorage
  quota failures, multiple tabs, and clearing site data. Browser storage remains
  a beta implementation; no service worker guarantees an offline application load.
- [ ] Keep plan and forecast limitations visible: manually entered schedules do
  not establish that payments settled. Verify arithmetic conventions against the
  product documentation, including two-decimal currencies and loan first-period
  interest.

## Server deployment and operations

- [ ] Follow [backend setup](backend.md). Provision PostgreSQL with least-privilege
  credentials, encrypted disks, private networking, and a tested migration process.
  Configure `DATABASE_URL`, `DATABASE_USER`, and `DATABASE_PASSWORD` in the
  deployment secret store, never committed files.
- [ ] Configure a real HTTPS `PUBLIC_URL`, TLS termination, strict browser origin
  allowlists, proxy limits/timeouts, and certificate renewal. Production must not
  use `TREASURY_ENV=development` or expose PostgreSQL to the public internet.
- [ ] Configure encrypted database and host backups, retention, and a restore
  drill. Document recovery targets and the treatment of deleted accounts in
  backups. Local JSON exports contain plaintext financial information.
- [ ] Exercise concurrent sync from two real devices, offline edits, clock skew,
  stale forms, tombstones, account switching, same user ID across different
  servers, token expiry, server outages, and reconnection after interruption.
- [ ] Verify monitoring, health checks, structured logs without financial payloads
  or tokens, rate limits, alert delivery, resource ceilings, dependency patching,
  and a tested rollback procedure. The included test environment is not a load
  or penetration test.

## Authentication and account lifecycle

- [ ] Register production Google, GitHub, and Discord OAuth applications as
  needed. Supply each provider's client ID and secret and register the exact
  `/v1/auth/oauth/{provider}/callback` redirect on the configured public origin.
  Verify the browser/device confirmation flow with real provider accounts,
  minimal scopes, expiry/cancellation, and provider-specific error responses.
- [ ] Configure SMTP host, port, credentials, sender, and TLS mode. Verify sender
  domain authentication, deliverability, password-reset and verification emails,
  one-use/expiry behavior, and account-enumeration resistance against the real
  deployment. Recovery cannot be considered operational without a mail service.
- [ ] Verify password hashing resources, session revocation, reauthentication,
  sign-out behavior, and sensitive information redaction. Sessions currently
  remain in memory and require sign-in again after application restart; persistent
  session storage would need platform keychain/keystore support and its own review.
- [ ] Exercise permanent remote account deletion plus local purge. Other devices
  retain their offline local copies until cleared; document that boundary and the
  backup erasure process. Soft-deleted financial records remain tombstones until
  the dedicated erasure flow removes them.

## Product and release administration

- [ ] Publish privacy, retention, support, and account-erasure documentation for
  the actual service operator and jurisdiction. Describe manual entry, optional
  server sync, and the absence of end-to-end encryption accurately.
- [ ] Confirm ownership/licensing and release suitability of dependencies,
  including the Material 3 and lifecycle prerelease versions presently pinned in
  the version catalog. Review dependency update pull requests before merging.
- [ ] Confirm release notes, support contact, version numbers, icons, screenshots,
  accessibility review, and data migration/rollback notes. Ship only after the
  platform and deployment checks applicable to that release are complete.
