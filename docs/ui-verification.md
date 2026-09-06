# UI verification

Run the desktop client tests with:

```shell
./gradlew :composeApp:jvmTest
```

`TreasuryUiTest` runs the actual shared `TreasuryHome`, editor dialogs, theme,
`LocalRepository`, and `DefaultCalendarEngine`. The test store is in memory so
these flows never read or modify a user's local profile. SQLDelight durability,
owner isolation, rollback, and purge behavior have separate integration tests.

`AppStartupTest` additionally launches `App()` itself through Koin, the controller,
and the JVM storage factory. It redirects the data directory into an isolated
folder under `composeApp/build/startup-test-data`, creates an account through the
UI, disposes the composition and waits for SQLite to close, then starts a fresh
`App()` and verifies the saved balance. It never opens the normal user profile.

The Compose tests exercise:

- First-account creation, missing-name validation, exact opening-balance entry,
  and adding a monthly subscription.
- Month, week, day, and pay-period navigation, including a period before the
  configured payday anchor.
- A phone-width calendar and entry dialog, scrolling, and cancelling an editor.
- Editing a one-time entry directly and changing only one recurring instance.
- Creating a biweekly BNPL plan and saving the exact schedule shown in preview.
- Reading insights and account balances at phone width.
- Cancelling account deletion, confirming it, and retaining tombstones for the
  account and its dependent entries and plans.
- Rejecting fractional cents without changing the repository.

The same tests write PNG artifacts under `composeApp/build/qa`:

- `desktop-calendar.png` and `desktop-created-account.png`, 1360 × 900.
- `desktop-plan-preview.png`, 1360 × 900, with the repayment preview scrolled into
  view.
- `mobile-calendar-top.png`, `mobile-calendar-grid.png`, and
  `mobile-calendar-agenda.png`, 390 × 844, covering the scrolling calendar.
- `mobile-entry-dialog.png`, `mobile-insights.png`, and `mobile-accounts.png`,
  390 × 844.

These are rendered screenshots for human review, not approved golden images or
pixel-difference assertions. Fixtures use the current local month so calendar
content remains relevant; the images can consequently differ between dates,
operating-system fonts, and rendering versions.

This verification covers shared Compose behavior on the JVM. A phone-sized JVM
surface is not an Android or iOS device test: platform keyboards, accessibility
services, window insets, lifecycle transitions, native database drivers, and
signed release packages still need platform-specific validation. WebAssembly
compilation checks shared source portability but is not a browser interaction
test.

The tests use the v2 Compose testing API and its configurable desktop rendering
surface. See the [official Compose testing documentation](https://kotlinlang.org/docs/multiplatform/compose-test.html).
