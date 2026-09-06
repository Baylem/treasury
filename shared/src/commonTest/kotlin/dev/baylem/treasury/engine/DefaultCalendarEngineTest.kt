package dev.baylem.treasury.engine

import dev.baylem.treasury.domain.*
import kotlinx.datetime.LocalDate
import kotlin.test.*
import kotlin.time.Instant

class DefaultCalendarEngineTest {
    private val engine = DefaultCalendarEngine()
    private fun date(value: String) = LocalDate.parse(value)
    private fun meta(id: String, owner: String = "owner") = EntityMeta(id, owner,
        Instant.parse("2026-01-01T00:00:00Z"), Instant.parse("2026-01-01T00:00:00Z"))
    private fun account(balance: Long = 0) = Account(meta("account"), "Checking", "USD", balance, date("2026-01-01"))
    private fun entry(start: String, recurrence: RecurrenceRule? = null, amount: Long = 100) =
        FinancialEntry(meta("entry"), "account", "Rent", amount, EntryDirection.EXPENSE, date(start), recurrence = recurrence)
    private fun occurrences(entry: FinancialEntry, start: String, end: String) = engine.expand(
        TreasurySnapshot(accounts = listOf(account()), entries = listOf(entry)), "owner", DateWindow(date(start), date(end)))

    @Test fun monthlyClippingRetainsOriginalAnchor() {
        val result = occurrences(entry("2026-01-31", RecurrenceRule(Frequency.MONTHLY)), "2026-01-01", "2026-05-31")
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30", "2026-05-31"), result.map { it.date.toString() })
    }

    @Test fun recurrenceCountIsRelativeToSeriesStart() {
        val result = occurrences(entry("2026-01-01", RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(3))), "2026-01-03", "2026-01-10")
        assertEquals(listOf(date("2026-01-03")), result.map { it.date })
    }

    @Test fun yearlyLeapDayReturnsInLeapYears() {
        val result = occurrences(entry("2024-02-29", RecurrenceRule(Frequency.YEARLY)), "2025-01-01", "2028-12-31")
        assertEquals(listOf("2025-02-28", "2026-02-28", "2027-02-28", "2028-02-29"), result.map { it.date.toString() })
    }

    @Test fun movedOccurrenceCanEnterWindowFromOutside() {
        val source = entry("2026-01-31", RecurrenceRule(Frequency.MONTHLY))
        val override = OccurrenceOverride(meta("override"), source.id, date("2026-01-31"), OverrideAction.Replace(date("2026-02-02"), 450))
        val result = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = listOf(override)), "owner", DateWindow(date("2026-02-01"), date("2026-02-05")))
        assertEquals(1, result.size)
        assertEquals(450L, result.single().amountMinor)
        assertEquals(date("2026-02-02"), result.single().date)
    }

    @Test fun installmentRemainderDoesNotLoseMoney() {
        val plan = PaymentPlan(meta("plan"), "account", "Sofa", 10000, date("2026-01-31"), 3, PlanKind.INSTALLMENT)
        val result = engine.schedulePlan(plan)
        assertEquals(listOf(3334L, 3333L, 3333L), result.map { it.amountMinor })
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), result.map { it.date.toString() })
        assertEquals(10000L, result.sumOf { it.principalMinor })
    }

    @Test fun amortizedScheduleSettlesPrincipalExactly() {
        val plan = PaymentPlan(meta("plan"), "account", "Loan", 100000, date("2026-01-31"), 12, PlanKind.AMORTIZED, annualRateBasisPoints = 1200)
        val result = engine.schedulePlan(plan)
        assertEquals(12, result.size)
        assertEquals(100000L, result.sumOf { it.principalMinor })
        assertEquals(0L, result.last().remainingPrincipalMinor)
        assertEquals(1000L, result.first().interestMinor)
        assertEquals(1, result.dropLast(1).map { it.amountMinor }.distinct().size)
        assertTrue(result.last().amountMinor <= result.first().amountMinor)
    }

    @Test fun forecastCarriesEarlierTransactionsIntoOpeningBalance() {
        val source = entry("2026-01-03", amount = 200)
        val result = engine.forecast(TreasurySnapshot(listOf(account(1000)), listOf(source)), "owner", DateWindow(date("2026-01-05"), date("2026-01-07")))
        assertEquals(800L, result.openingBalanceMinor)
        assertEquals(listOf(800L, 800L, 800L), result.days.map { it.closingBalanceMinor })
    }

    @Test fun payPeriodsIncludePeriodContainingWindowStart() {
        val periods = engine.payPeriods(date("2026-01-02"), PayCadence.BIWEEKLY, DateWindow(date("2026-01-05"), date("2026-01-17")))
        assertEquals(listOf("2026-01-02", "2026-01-16"), periods.map { it.start.toString() })
        assertEquals(date("2026-01-15"), periods.first().endInclusive)
    }

    @Test fun oneTimeEntriesRespectInclusiveWindow() {
        val source = entry("2026-02-01")
        assertEquals(1, occurrences(source, "2026-02-01", "2026-02-01").size)
        assertTrue(occurrences(source, "2026-02-02", "2026-02-28").isEmpty())
        assertTrue(occurrences(source, "2026-01-01", "2026-01-31").isEmpty())
    }

    @Test fun recurrenceUntilIncludesLastDate() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.WEEKLY, end = RecurrenceEnd.Until(date("2026-01-15"))))
        assertEquals(listOf("2026-01-01", "2026-01-08", "2026-01-15"), occurrences(source, "2026-01-01", "2026-02-01").map { it.date.toString() })
    }

    @Test fun recurrenceIntervalsDoNotRestartAtWindowStart() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.WEEKLY, interval = 2))
        assertEquals(listOf("2026-01-15", "2026-01-29"), occurrences(source, "2026-01-08", "2026-01-31").map { it.date.toString() })
    }

    @Test fun recurrenceEndsBeforeWindowReturnNoDates() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.MONTHLY, end = RecurrenceEnd.Count(2)))
        assertTrue(occurrences(source, "2030-01-01", "2030-12-31").isEmpty())
    }

    @Test fun recurrenceStartsAfterWindowReturnNoDates() {
        val source = entry("2030-01-01", RecurrenceRule(Frequency.DAILY))
        assertTrue(occurrences(source, "2026-01-01", "2026-12-31").isEmpty())
    }

    @Test fun skipOverrideOnlyRemovesOneOccurrence() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.DAILY, end = RecurrenceEnd.Count(3)))
        val skip = OccurrenceOverride(meta("skip"), source.id, date("2026-01-02"), OverrideAction.Skip)
        val result = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = listOf(skip)), "owner", DateWindow(date("2026-01-01"), date("2026-01-10")))
        assertEquals(listOf("2026-01-01", "2026-01-03"), result.map { it.date.toString() })
    }

    @Test fun movedOccurrenceLeavesOldWindowAndKeepsStableId() {
        val source = entry("2026-01-31")
        val replacement = OccurrenceOverride(meta("move"), source.id, source.date, OverrideAction.Replace(date("2026-02-02"), 700, "New name"))
        val snapshot = TreasurySnapshot(listOf(account()), listOf(source), overrides = listOf(replacement))
        assertTrue(engine.expand(snapshot, "owner", DateWindow(date("2026-01-01"), date("2026-01-31"))).isEmpty())
        val result = engine.expand(snapshot, "owner", DateWindow(date("2026-02-01"), date("2026-02-28"))).single()
        assertEquals("entry:2026-01-31", result.id)
        assertEquals("New name", result.title)
        assertTrue(result.isOverride)
    }

    @Test fun invalidOverrideCannotInventOccurrences() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.MONTHLY, end = RecurrenceEnd.Count(2)))
        val exceptions = listOf("2025-12-01", "2026-01-02", "2026-03-01").mapIndexed { index, original ->
            OccurrenceOverride(meta("invalid$index"), source.id, date(original), OverrideAction.Replace(date("2026-04-01"), 700))
        }
        val result = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = exceptions), "owner", DateWindow(date("2026-04-01"), date("2026-04-30")))
        assertTrue(result.isEmpty())
    }

    @Test fun deletedOverrideRestoresOriginalOccurrence() {
        val source = entry("2026-01-01")
        val deleted = meta("deleted").let { it.copy(deletedAt = it.updatedAt) }
        val skip = OccurrenceOverride(deleted, source.id, source.date, OverrideAction.Skip)
        val result = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = listOf(skip)), "owner", DateWindow(source.date, source.date))
        assertEquals(1, result.size)
    }

    @Test fun concurrentOverridesUseDeterministicLastWriteWins() {
        val source = entry("2026-01-01")
        val old = OccurrenceOverride(meta("old"), source.id, source.date, OverrideAction.Skip)
        val recent = OccurrenceOverride(meta("new").copy(updatedAt = Instant.parse("2026-01-02T00:00:00Z")), source.id, source.date, OverrideAction.Replace(source.date, 900))
        fun expand(overrides: List<OccurrenceOverride>) = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = overrides), "owner", DateWindow(source.date, source.date))
        assertEquals(expand(listOf(old, recent)), expand(listOf(recent, old)))
        assertEquals(900L, expand(listOf(old, recent)).single().amountMinor)
    }

    @Test fun expansionFiltersOwnersAndDeletedParents() {
        val source = entry("2026-01-01")
        val other = source.copy(meta = meta("other", "another-owner"))
        val deleted = source.copy(meta = meta("deleted").let { it.copy(deletedAt = it.updatedAt) })
        val snapshot = TreasurySnapshot(listOf(account()), listOf(source, other, deleted))
        assertEquals(1, engine.expand(snapshot, "owner", DateWindow(source.date, source.date)).size)
        assertTrue(engine.expand(snapshot, "another-owner", DateWindow(source.date, source.date)).isEmpty())
        assertTrue(engine.expand(snapshot.copy(accounts = listOf(account().let { it.copy(meta = it.meta.copy(deletedAt = it.updatedAt)) })), "owner", DateWindow(source.date, source.date)).isEmpty())
    }

    @Test fun bnplCanBePaidEveryFourteenDays() {
        val plan = PaymentPlan(meta("plan"), "account", "Purchase", 10000, date("2026-01-31"), 4, PlanKind.BNPL, paymentIntervalDays = 14)
        assertEquals(listOf("2026-01-31", "2026-02-14", "2026-02-28", "2026-03-14"), engine.schedulePlan(plan).map { it.date.toString() })
    }

    @Test fun zeroInterestAmortizationMatchesInstallments() {
        val plan = PaymentPlan(meta("plan"), "account", "Purchase", 10001, date("2026-01-01"), 4, PlanKind.AMORTIZED)
        assertEquals(engine.schedulePlan(plan.copy(kind = PlanKind.INSTALLMENT)), engine.schedulePlan(plan))
    }

    @Test fun planPropertiesHoldAcrossRatesTermsAndAmounts() {
        for (principal in listOf(1L, 3L, 100L, 10001L, 12345678L, 9007199254740993L)) {
            for (rate in listOf(0, 1, 1200, 4000)) {
                for (count in listOf(1, 3, 12, 360)) {
                    val plan = PaymentPlan(meta("plan"), "account", "Loan", principal, date("2026-01-31"), count, PlanKind.AMORTIZED, rate)
                    val result = engine.schedulePlan(plan)
                    assertEquals(count, result.size)
                    assertEquals(principal, result.fold(0L) { sum, payment -> Money.add(sum, payment.principalMinor) })
                    assertEquals(0L, result.last().remainingPrincipalMinor)
                    assertTrue(result.all { it.principalMinor >= 0 && it.interestMinor >= 0 && it.amountMinor == Money.add(it.principalMinor, it.interestMinor) })
                    assertTrue(result.zipWithNext().all { (a, b) -> b.remainingPrincipalMinor <= a.remainingPrincipalMinor })
                }
            }
        }
    }

    @Test fun dailyInterestUsesActual365Convention() {
        val plan = PaymentPlan(meta("plan"), "account", "Loan", 100000, date("2026-01-01"), 1, PlanKind.AMORTIZED, 3650, paymentIntervalDays = 10)
        assertEquals(1000L, engine.schedulePlan(plan).single().interestMinor)
    }

    @Test fun deletedPlansProduceNoPayments() {
        val deleted = meta("plan").let { it.copy(deletedAt = it.updatedAt) }
        val plan = PaymentPlan(deleted, "account", "Loan", 1000, date("2026-01-01"), 4, PlanKind.BNPL)
        assertTrue(engine.schedulePlan(plan).isEmpty())
    }

    @Test fun forecastIncludesInstallmentsAndAllCalendarDays() {
        val plan = PaymentPlan(meta("plan"), "account", "Loan", 1000, date("2026-01-02"), 4, PlanKind.BNPL, paymentIntervalDays = 1)
        val result = engine.forecast(TreasurySnapshot(listOf(account(1500)), plans = listOf(plan)), "owner", DateWindow(date("2026-01-01"), date("2026-01-06")))
        assertEquals(6, result.days.size)
        assertEquals(1000L, result.totalExpenseMinor)
        assertEquals(500L, result.closingBalanceMinor)
        assertEquals(500L, result.lowestBalanceMinor)
    }

    @Test fun forecastIgnoresTransactionsBeforeBalanceMeasurement() {
        val snapshot = TreasurySnapshot(listOf(account(1500).copy(balanceDate = date("2026-01-10"))), listOf(entry("2026-01-02", amount = 250)))
        val result = engine.forecast(snapshot, "owner", DateWindow(date("2026-01-10"), date("2026-01-11")))
        assertEquals(1500L, result.openingBalanceMinor)
    }

    @Test fun futureOpeningBalancesAreAdjustmentsNotIncome() {
        val snapshot = TreasurySnapshot(listOf(account(1500).copy(balanceDate = date("2026-01-03"))), listOf(entry("2026-01-03", amount = 250)))
        val result = engine.forecast(snapshot, "owner", DateWindow(date("2026-01-01"), date("2026-01-04")))
        assertEquals(0L, result.openingBalanceMinor)
        assertEquals(0L, result.totalIncomeMinor)
        assertEquals(1500L, result.days[2].balanceAdjustmentMinor)
        assertEquals(1250L, result.closingBalanceMinor)
    }

    @Test fun forecastKeepsCurrenciesSeparate() {
        val euro = account().copy(meta = meta("euro"), currency = "EUR")
        val snapshot = TreasurySnapshot(listOf(account(1000), euro))
        val window = DateWindow(date("2026-01-01"), date("2026-01-02"))
        assertFailsWith<IllegalArgumentException> { engine.forecast(snapshot, "owner", window) }
        assertEquals("USD", engine.forecast(snapshot, "owner", window, "account").currency)
        assertFailsWith<IllegalArgumentException> { engine.forecast(snapshot, "owner", window, "missing") }
    }

    @Test fun emptySnapshotForecastIsZero() {
        val result = engine.forecast(TreasurySnapshot(), "owner", DateWindow(date("2026-01-01"), date("2026-01-02")))
        assertNull(result.currency)
        assertEquals(0L, result.closingBalanceMinor)
        assertEquals(2, result.days.size)
    }

    @Test fun forecastHistoricalSameDayCashFlowMatchesDailySemantics() {
        val income = entry("2026-01-01", amount = 100).copy(meta = meta("income"), title = "A income", direction = EntryDirection.INCOME)
        val expense = entry("2026-01-01", amount = 100).copy(title = "Z expense")
        val snapshot = TreasurySnapshot(listOf(account(Long.MAX_VALUE)), listOf(income, expense))
        val current = engine.forecast(snapshot, "owner", DateWindow(date("2026-01-01"), date("2026-01-01")))
        val future = engine.forecast(snapshot, "owner", DateWindow(date("2026-01-02"), date("2026-01-02")))
        assertEquals(Long.MAX_VALUE, current.closingBalanceMinor)
        assertEquals(current.closingBalanceMinor, future.openingBalanceMinor)
    }

    @Test fun unboundedWindowsAndAncientOpeningBalancesAreRejected() {
        assertFailsWith<IllegalArgumentException> { DateWindow(date("1800-01-01"), date("2026-01-01")) }
        val ancient = account().copy(balanceDate = date("1800-01-01"))
        assertFailsWith<IllegalArgumentException> { engine.forecast(TreasurySnapshot(listOf(ancient)), "owner", DateWindow(date("2026-01-01"), date("2026-01-01"))) }
    }

    @Test fun recurrenceDoesNotWalkFromAncientAnchorToModernWindow() {
        val source = entry("1000-01-01", RecurrenceRule(Frequency.DAILY))
        assertEquals(listOf("2026-01-01", "2026-01-02"), occurrences(source, "2026-01-01", "2026-01-02").map { it.date.toString() })
    }

    @Test fun duplicateOverrideTieUsesStableIdRegardlessOfListOrder() {
        val source = entry("2026-01-01", RecurrenceRule(Frequency.MONTHLY))
        val left = OccurrenceOverride(meta("a"), source.id, source.date, OverrideAction.Replace(source.date, 100))
        val right = OccurrenceOverride(meta("z"), source.id, source.date, OverrideAction.Replace(source.date, 200))
        fun expand(exceptions: List<OccurrenceOverride>) = engine.expand(TreasurySnapshot(listOf(account()), listOf(source), overrides = exceptions), "owner", DateWindow(source.date, source.date))
        assertEquals(expand(listOf(left, right)), expand(listOf(right, left)))
        assertEquals(200L, expand(listOf(left, right)).single().amountMinor)
    }

    @Test fun monthlyPayPeriodsRetainClippedAnchor() {
        val periods = engine.payPeriods(date("2026-01-31"), PayCadence.MONTHLY, DateWindow(date("2026-02-10"), date("2026-04-10")))
        assertEquals(listOf("2026-01-31", "2026-02-28", "2026-03-31"), periods.map { it.start.toString() })
        assertEquals(listOf("2026-02-27", "2026-03-30", "2026-04-29"), periods.map { it.endInclusive.toString() })
    }

    @Test fun payPeriodsWorkBeforeAnchor() {
        val periods = engine.payPeriods(date("2026-02-06"), PayCadence.BIWEEKLY, DateWindow(date("2026-01-01"), date("2026-01-10")))
        assertEquals(listOf("2025-12-26", "2026-01-09"), periods.map { it.start.toString() })
    }

    @Test fun semimonthlyPeriodsUseFirstAndSixteenth() {
        val periods = engine.payPeriods(date("2026-09-06"), PayCadence.SEMIMONTHLY, DateWindow(date("2026-02-01"), date("2026-03-02")))
        assertEquals(listOf("2026-02-01", "2026-02-16", "2026-03-01"), periods.map { it.start.toString() })
        assertEquals(date("2026-02-28"), periods[1].endInclusive)
    }

    @Test fun healthReportsDeficitsAndUndefinedSavingsWithoutIncome() {
        val result = engine.forecast(TreasurySnapshot(listOf(account(100)), listOf(entry("2026-01-02", amount = 200))), "owner", DateWindow(date("2026-01-01"), date("2026-01-03")))
        val health = engine.health(result)
        assertEquals(HealthStatus.AT_RISK, health.status)
        assertEquals(2, health.negativeBalanceDays)
        assertEquals(date("2026-01-02"), health.firstNegativeDate)
        assertEquals(-200L, health.netCashFlowMinor)
        assertNull(health.savingsRateBasisPoints)
    }

    @Test fun healthSavingsRateUsesExactBasisPoints() {
        val income = entry("2026-01-01", amount = 10000).copy(meta = meta("income"), direction = EntryDirection.INCOME)
        val expense = entry("2026-01-02", amount = 7533)
        val forecast = engine.forecast(TreasurySnapshot(listOf(account(1000)), listOf(income, expense)), "owner", DateWindow(date("2026-01-01"), date("2026-01-03")))
        assertEquals(2467, engine.health(forecast).savingsRateBasisPoints)
        assertEquals(HealthStatus.HEALTHY, engine.health(forecast).status)
    }

    @Test fun healthHandlesVeryLargeAndVerySmallIncomeRatios() {
        fun health(income: Long, expense: Long): FinancialHealth {
            val window = DateWindow(date("2026-01-01"), date("2026-01-01"))
            return engine.health(Forecast(window, "USD", 0, listOf(ForecastDay(window.start, 0, income, expense, closingBalanceMinor = income - expense)), emptyList()))
        }
        assertEquals(5000, health(Long.MAX_VALUE - 1, (Long.MAX_VALUE - 1) / 2).savingsRateBasisPoints)
        assertEquals(-Int.MAX_VALUE, health(1, Long.MAX_VALUE).savingsRateBasisPoints)
        assertEquals(-2147480000, health(1, 214749).savingsRateBasisPoints)
    }
}
