# Shared financial engine

`shared/src/commonMain/kotlin/dev/baylem/treasury/engine` is the only implementation
of calendar expansion, repayment schedules, forecasts, pay periods, and health
metrics. It has no storage, network, clock, platform, or authentication dependency.
Clients and the server call the same `CalendarEngine` interface. Persisted rows
live in the domain package and enter through the owner-scoped repository seam.

## Amounts and dates

- Amounts are signed 64-bit integer **minor units**. Entry amounts and plan
  principal are nonnegative; entry direction determines whether money enters or
  leaves an account. Account opening balances can be negative.
- Addition, subtraction, multiplication, and aggregation detect overflow and
  fail explicitly. Decimal money is never converted through floating point.
- `Money.parseMinor` and `Money.formatMinor` use **two decimal places**. The
  application supports USD, EUR, GBP, CAD, and AUD, which share that convention.
  Parsing accepts an optional sign and plain decimal digits, including values
  beyond JavaScript's safe integer range. It rejects grouping separators,
  exponent notation, and more than two decimal places.
- Interest rounds half up to a minor unit per payment period. Integer rational
  arithmetic avoids both intermediate overflow and floating-point drift.
- Scheduled dates are `LocalDate`. Only sync metadata uses `kotlin.time.Instant`.
  Windows are inclusive at both ends; daylight-saving changes cannot shift an
  occurrence into a different calendar day.

## Recurrence and exceptions

An entry's date is its permanent recurrence anchor. Frequency may be daily,
weekly, monthly, or yearly, with an integer interval. A weekly series repeats on
the anchor's weekday. Multiple weekdays are represented by separate series.

Each monthly or yearly occurrence is calculated from the original anchor, never
from the previously clipped occurrence. January 31 becomes February 28 and then
March 31. February 29 becomes February 28 in nonleap years and returns to February
29 in leap years.

`RecurrenceEnd.Never` is bounded by the requested window. `Until` includes its
last date. `Count` counts scheduled instances from the series anchor, including
ones subsequently skipped or moved. A query beginning after the anchor does not
restart the count. The implementation jumps directly to the interval containing
the window start, including for centuries-old anchors.

Only exceptions are stored. `OverrideAction.Skip` suppresses one original
instance. `Replace` moves its date and changes its amount and optionally its
title. The instance keeps its ID and original-date source, so moving it does not
create a second instance. A moved instance can enter a window even if its
original day lies outside that window. An exception whose original date was
never in the series, or was after the series ended, creates no occurrence.

Deleted rows and deleted accounts are excluded. If concurrent devices create
different live override IDs for the same original instance, the greatest
`updatedAt`, then `revision`, then ID wins deterministically. Tombstones remain
stored for sync but are not active exceptions. The repository supersedes older
local exceptions using tombstones.

Occurrences are returned by date, title, and stable ID. This order is a display
order, **not** an intraday transaction ordering or a bank settlement model.

## Payment plans

`PaymentPlan.startDate` is the **first repayment due date**, not the date funds
were advanced. Monthly intervals use the same original-anchor clipping as
recurrence. `paymentIntervalDays` overrides monthly spacing; the application uses
14 days for ordinary pay-in-four BNPL schedules.

BNPL and installment plans have zero interest. Principal is split using integer
division, with one extra minor unit assigned to the earliest payments until the
remainder is exhausted. A 10,000-unit principal split three ways produces 3,334,
3,333, and 3,333. If there are more installments than minor units, later payments
may be zero; the schedule still contains the configured number of installments.

Interest-bearing plans use `PlanKind.AMORTIZED`. APR is integer basis points:
1,200 means 12.00%. Each payment includes one complete payment interval of
interest, including the first scheduled payment:

- Monthly spacing: outstanding principal × APR basis points × interval months ÷
  120,000.
- Fixed day spacing: outstanding principal × APR basis points × interval days ÷
  3,650,000, an Actual/365 fixed convention.

Interest is rounded half up each period. Binary search finds the smallest whole
minor-unit regular payment that settles the principal in the specified number
of payments. Each installment pays interest first, then reduces principal. The
last payment is reduced as needed; it never leaves a rounding balance. Very
small principals can settle before the configured final installment, with zero
payments thereafter. Principal components always sum exactly to the original
principal. Zero-rate amortized plans use the same exact split as installments.

These are explicit planning conventions, not a reconstruction of a lender's
contract. Origination fees, late fees, variable rates, lender-specific day-count
rules, and irregular first periods are not inferred. Additional charges can be
entered manually as expenses. Plan repayments are expenses in the forecast;
loan proceeds must be entered separately as income when applicable.

## Forecasts

An account's opening balance is measured **immediately before transactions on
its balance date**. Transactions before that date are already represented in
the balance and are excluded. Transactions on the balance date are included.

For a forecast beginning later, the engine expands earlier occurrences and
advances the recorded balance to the window start. Each historical date is
netted with the same rules used inside the forecast, avoiding dependence on
alphabetical display ordering.

An account with a balance date inside the requested window contributes zero
before that day. Its opening balance appears as `balanceAdjustmentMinor` on its
balance date, without being misclassified as income. Its scheduled income and
expenses on that day are then applied.

Every calendar day is returned, including empty days. Daily income and expenses
are summed separately, then their net is applied to the opening balance. The
result reports the closing balance and lowest balance across the initial
opening and all daily closes. It does not estimate temporary intraday overdrafts.

The engine never adds different currencies together or invents exchange rates.
Forecasting all accounts requires a single currency. A caller can select one
account to forecast it independently; an unknown account is rejected.

Forecasts represent manually entered scheduled cash flow. They do not verify
payment settlement or reconcile with a bank. Skipping or editing an occurrence
changes the projection; the application does not infer whether an item was paid.

## Pay periods

Weekly and biweekly periods begin on the anchor and repeat every 7 or 14 days.
Monthly periods use the anchor's day with month-end clipping. Semimonthly means
the **1st through 15th** and the **16th through month end**; its boundaries are
fixed, irrespective of the supplied anchor's day.

Periods end the day before the next boundary. Queries return full periods
overlapping the inclusive window, including the period containing its first
day. Dates before the anchor work in both directions. A one-day query always
returns its containing period, which is the calendar UI's pay-period window.

## Financial health

Health describes the selected forecast window, without extrapolating income or
giving financial advice. It reports income, expenses, net scheduled cash flow,
lowest projected balance, days with a negative closing balance, and the first
negative date. A negative initial opening balance also sets the first negative
date to the window start, even if that day's income restores a positive close.

Savings rate is `(income − expenses) / income` in integer basis points, truncated
toward zero. It is undefined (`null`) when scheduled income is zero. Extremely
large negative ratios saturate to the supported integer range. Status is:

- `AT_RISK` if the initial opening or any daily closing balance is negative.
- `WATCH` if scheduled expenses exceed income, or the lowest balance is zero.
- `HEALTHY` otherwise.

These labels summarize recorded projections. They are not a credit score,
investment assessment, or guarantee of future funds.

## Bounds and validation

Calendar windows are limited to 36,600 days (roughly 100 years). Forecast opening
balances may be at most 36,600 days before the requested start; older balances
need a newer recorded balance date. Expansion rejects more than 100,000 returned
occurrences. Recurrence count is at most 100,000 and interval is 1–1,200. Payment
plans have 1–1,200 payments and APR between zero and 100,000 basis points. These
bounds keep malformed or untrusted input from creating unbounded work.

The repository validates the complete graph, owner IDs, canonical UUID entity IDs, references,
and metadata chronology before persistence. Pure engine calls also scope active
rows to the requested owner and the owner's active accounts. Soft deletion
retains tombstones. Permanent erasure is a separate storage operation.

Run `./gradlew :shared:jvmTest` for the engine, model, money, and repository tests.
The common tests use portable camelCase names. WebAssembly compilation of both
the shared source and tests additionally checks that no JVM-only API leaked into
the engine. Native execution requires the corresponding platform toolchain.
