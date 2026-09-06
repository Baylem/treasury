package dev.baylem.treasury.engine

import dev.baylem.treasury.domain.*
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus

/** Deterministic, I/O-free calculations shared by every client and the server. */
class DefaultCalendarEngine : CalendarEngine {
    override fun expand(snapshot: TreasurySnapshot, ownerId: String, window: DateWindow): List<Occurrence> {
        val accounts = snapshot.accounts.filter { it.ownerId == ownerId && it.deletedAt == null }.map { it.id }.toSet()
        val result = mutableListOf<Occurrence>()
        val overrides = snapshot.overrides.filter { it.ownerId == ownerId && it.deletedAt == null }
            .groupBy { it.entryId }.mapValues { (_, values) ->
                values.groupBy { it.originalDate }.mapValues { (_, candidates) -> candidates.maxWith(
                    compareBy<OccurrenceOverride> { it.updatedAt }.thenBy { it.revision }.thenBy { it.id }) }
            }
        for (entry in snapshot.entries) {
            if (entry.ownerId != ownerId || entry.deletedAt != null || entry.accountId !in accounts) continue
            val exceptions = overrides[entry.id].orEmpty()
            for (date in occurrenceDates(entry, window)) {
                if (date in exceptions) continue
                result += entryOccurrence(entry, date)
                checkSize(result.size)
            }
            // Moved instances must be considered even when their original day is outside this window.
            for ((original, exception) in exceptions) {
                when (val action = exception.action) {
                    OverrideAction.Skip -> Unit
                    is OverrideAction.Replace -> if (action.date in window && isOccurrenceDate(entry, original)) {
                        result += entryOccurrence(entry, original).copy(date = action.date, amountMinor = action.amountMinor,
                            title = action.title ?: entry.title, isOverride = true)
                        checkSize(result.size)
                    }
                }
            }
        }
        for (plan in snapshot.plans) {
            if (plan.ownerId != ownerId || plan.deletedAt != null || plan.accountId !in accounts) continue
            if (plan.startDate > window.endInclusive) continue
            for (payment in schedulePlan(plan)) if (payment.date in window) {
                result += Occurrence("${plan.id}:payment:${payment.installmentNumber}", plan.accountId, plan.title,
                    payment.amountMinor, EntryDirection.EXPENSE, payment.date, plan.category,
                    OccurrenceSource.Plan(plan.id, payment.installmentNumber))
                checkSize(result.size)
            }
        }
        return result.sortedWith(compareBy<Occurrence> { it.date }.thenBy { it.title }.thenBy { it.id })
    }

    override fun schedulePlan(plan: PaymentPlan): List<PlanPayment> {
        if (plan.deletedAt != null) return emptyList()
        val interestBearing = when (plan.kind) {
            PlanKind.BNPL, PlanKind.INSTALLMENT -> false
            PlanKind.AMORTIZED -> plan.annualRateBasisPoints > 0
        }
        val count = plan.installments
        val principal = plan.principalMinor
        val fixed = if (interestBearing) amortizedPayment(plan) else principal / count
        val remainder = if (interestBearing) 0L else principal % count
        var remaining = principal
        return (0 until count).map { index ->
            val interest = if (interestBearing) interestFor(remaining, plan) else 0L
            val principalPart = if (interestBearing) minOf(remaining, Money.subtract(fixed, interest))
                else fixed + if (index.toLong() < remainder) 1 else 0
            val payment = Money.add(principalPart, interest)
            remaining = Money.subtract(remaining, principalPart)
            PlanPayment(plan.id, index + 1, paymentDate(plan, index), principalPart, interest, payment, remaining)
        }.also { check(remaining == 0L) { "Amortization did not settle the principal" } }
    }

    override fun forecast(snapshot: TreasurySnapshot, ownerId: String, window: DateWindow, accountId: String?): Forecast {
        val accounts = snapshot.accounts.filter { it.ownerId == ownerId && it.deletedAt == null && (accountId == null || it.id == accountId) }
        require(accountId == null || accounts.isNotEmpty()) { "Account was not found" }
        val currencies = accounts.map { it.currency }.toSet()
        require(currencies.size <= 1) { "Select an account to forecast different currencies separately" }
        val accountMap = accounts.associateBy { it.id }
        val scoped = snapshot.copy(accounts = accounts)
        var balance = accounts.filter { it.balanceDate <= window.start }.fold(0L) { total, account -> Money.add(total, account.openingBalanceMinor) }
        // Bring each account's recorded opening balance forward to the requested window.
        val earliest = accounts.minOfOrNull { it.balanceDate }
        if (earliest != null && earliest < window.start) {
            require(window.start.toEpochDays() - earliest.toEpochDays() <= 36_600) {
                "Opening balances are more than 100 years before this forecast; record a newer balance date"
            }
            val through = window.start.plus(-1, DateTimeUnit.DAY)
            val historical = expand(scoped, ownerId, DateWindow(earliest, through))
                .filter { it.date >= accountMap.getValue(it.accountId).balanceDate }.groupBy { it.date }
            for (occurrencesOnDay in historical.values) {
                var income = 0L
                var expense = 0L
                for (occurrence in occurrencesOnDay) when (occurrence.direction) {
                    EntryDirection.INCOME -> income = Money.add(income, occurrence.amountMinor)
                    EntryDirection.EXPENSE -> expense = Money.add(expense, occurrence.amountMinor)
                }
                balance = Money.add(balance, Money.subtract(income, expense))
            }
        }
        val opening = balance
        val occurrences = expand(scoped, ownerId, window).filter { it.date >= accountMap.getValue(it.accountId).balanceDate }
        val byDate = occurrences.groupBy { it.date }
        val days = mutableListOf<ForecastDay>()
        var day = window.start
        while (true) {
            var income = 0L
            var expense = 0L
            for (occurrence in byDate[day].orEmpty()) when (occurrence.direction) {
                EntryDirection.INCOME -> income = Money.add(income, occurrence.amountMinor)
                EntryDirection.EXPENSE -> expense = Money.add(expense, occurrence.amountMinor)
            }
            val adjustment = if (day == window.start) 0L else accounts.filter { it.balanceDate == day }
                .fold(0L) { total, account -> Money.add(total, account.openingBalanceMinor) }
            val closing = Money.add(Money.add(balance, adjustment), Money.subtract(income, expense))
            days += ForecastDay(day, balance, income, expense, adjustment, closing)
            balance = closing
            if (day == window.endInclusive) break
            day = day.plus(1, DateTimeUnit.DAY)
        }
        return Forecast(window, currencies.singleOrNull(), opening, days, occurrences)
    }

    override fun payPeriods(anchor: LocalDate, cadence: PayCadence, window: DateWindow): List<PayPeriod> {
        fun boundary(index: Int): LocalDate = when (cadence) {
            PayCadence.WEEKLY -> anchor.plus(checkedInt(index.toLong() * 7), DateTimeUnit.DAY)
            PayCadence.BIWEEKLY -> anchor.plus(checkedInt(index.toLong() * 14), DateTimeUnit.DAY)
            PayCadence.MONTHLY -> anchor.plus(index, DateTimeUnit.MONTH)
            PayCadence.SEMIMONTHLY -> {
                val monthIndex = floorDiv(index, 2)
                val first = LocalDate(anchor.year, anchor.month, 1).plus(monthIndex, DateTimeUnit.MONTH)
                if (index - monthIndex * 2 == 0) first else first.plus(15, DateTimeUnit.DAY)
            }
        }
        var index = when (cadence) {
            PayCadence.WEEKLY -> floorDiv(checkedInt(window.start.toEpochDays() - anchor.toEpochDays()), 7)
            PayCadence.BIWEEKLY -> floorDiv(checkedInt(window.start.toEpochDays() - anchor.toEpochDays()), 14)
            PayCadence.MONTHLY -> monthDistance(anchor, window.start)
            PayCadence.SEMIMONTHLY -> monthDistance(anchor, window.start) * 2 + if (window.start.day >= 16) 1 else 0
        }
        while (boundary(index) > window.start) index--
        while (boundary(index + 1) <= window.start) index++
        val result = mutableListOf<PayPeriod>()
        var start = boundary(index)
        while (start <= window.endInclusive) {
            val next = boundary(++index)
            result += PayPeriod(start, next.plus(-1, DateTimeUnit.DAY))
            start = next
        }
        return result
    }

    override fun health(forecast: Forecast): FinancialHealth {
        val income = forecast.totalIncomeMinor
        val expense = forecast.totalExpenseMinor
        val net = Money.subtract(income, expense)
        val negative = forecast.days.filter { it.closingBalanceMinor < 0 }
        // Ratios are integer basis points. Extreme ratios saturate without converting money to floats.
        val rate = if (income == 0L) null else {
            val magnitude = if (net >= 0) net else -net
            val value = ratioBasisPoints(magnitude, income)
            if (net >= 0) value else -value
        }
        val status = when {
            forecast.lowestBalanceMinor < 0 -> HealthStatus.AT_RISK
            net < 0 || forecast.lowestBalanceMinor == 0L -> HealthStatus.WATCH
            else -> HealthStatus.HEALTHY
        }
        return FinancialHealth(income, expense, net, forecast.lowestBalanceMinor, negative.size,
            if (forecast.openingBalanceMinor < 0) forecast.window.start else negative.firstOrNull()?.date, rate, status)
    }

    private fun entryOccurrence(entry: FinancialEntry, original: LocalDate) = Occurrence(
        "${entry.id}:$original", entry.accountId, entry.title, entry.amountMinor, entry.direction,
        original, entry.category, OccurrenceSource.Entry(entry.id, original))

    private fun occurrenceDates(entry: FinancialEntry, window: DateWindow): List<LocalDate> {
        val rule = entry.recurrence ?: return if (entry.date in window) listOf(entry.date) else emptyList()
        if (entry.date > window.endInclusive) return emptyList()
        var index = approximateIndex(entry.date, rule, window.start).coerceAtLeast(0)
        val lastIndex = approximateIndex(entry.date, rule, window.endInclusive)
        val result = mutableListOf<LocalDate>()
        while (index <= lastIndex) {
            if (rule.end is RecurrenceEnd.Count && index >= rule.end.count) break
            val date = recurrenceDate(entry.date, rule, index)
            if (date > window.endInclusive) break
            val ended = when (val end = rule.end) {
                RecurrenceEnd.Never -> false
                is RecurrenceEnd.Count -> index >= end.count
                is RecurrenceEnd.Until -> date > end.date
            }
            if (ended) break
            if (date >= window.start) result += date
            index++
            checkSize(result.size)
        }
        return result
    }

    private fun isOccurrenceDate(entry: FinancialEntry, date: LocalDate): Boolean {
        if (date < entry.date) return false
        val rule = entry.recurrence ?: return date == entry.date
        val index = approximateIndex(entry.date, rule, date)
        if (recurrenceDate(entry.date, rule, index) != date) return false
        return when (val end = rule.end) {
            RecurrenceEnd.Never -> true
            is RecurrenceEnd.Count -> index < end.count
            is RecurrenceEnd.Until -> date <= end.date
        }
    }

    private fun approximateIndex(anchor: LocalDate, rule: RecurrenceRule, date: LocalDate): Int = when (rule.frequency) {
        Frequency.DAILY -> checkedInt((date.toEpochDays() - anchor.toEpochDays()) / rule.interval)
        Frequency.WEEKLY -> checkedInt((date.toEpochDays() - anchor.toEpochDays()) / (7 * rule.interval))
        Frequency.MONTHLY -> monthDistance(anchor, date) / rule.interval
        Frequency.YEARLY -> (date.year - anchor.year) / rule.interval
    }

    private fun recurrenceDate(anchor: LocalDate, rule: RecurrenceRule, index: Int): LocalDate {
        val interval = checkedInt(index.toLong() * rule.interval)
        return when (rule.frequency) {
            Frequency.DAILY -> anchor.plus(interval, DateTimeUnit.DAY)
            Frequency.WEEKLY -> anchor.plus(checkedInt(interval.toLong() * 7), DateTimeUnit.DAY)
            Frequency.MONTHLY -> anchor.plus(interval, DateTimeUnit.MONTH)
            Frequency.YEARLY -> anchor.plus(interval, DateTimeUnit.YEAR)
        }
    }

    private fun paymentDate(plan: PaymentPlan, index: Int): LocalDate = plan.paymentIntervalDays?.let {
        plan.startDate.plus(index * it, DateTimeUnit.DAY)
    } ?: plan.startDate.plus(index * plan.intervalMonths, DateTimeUnit.MONTH)

    private fun interestFor(principal: Long, plan: PaymentPlan): Long = if (plan.paymentIntervalDays != null)
        Money.multiplyDivide(principal, plan.annualRateBasisPoints.toLong() * plan.paymentIntervalDays, 3_650_000)
    else Money.multiplyDivide(principal, plan.annualRateBasisPoints.toLong() * plan.intervalMonths, 120_000)

    private fun amortizedPayment(plan: PaymentPlan): Long {
        val firstInterest = interestFor(plan.principalMinor, plan)
        var low = maxOf(plan.principalMinor / plan.installments, firstInterest)
        var high = if (plan.principalMinor > Long.MAX_VALUE - firstInterest) Long.MAX_VALUE else plan.principalMinor + firstInterest
        fun settles(payment: Long): Boolean {
            var remaining = plan.principalMinor
            repeat(plan.installments) {
                val interest = interestFor(remaining, plan)
                if (payment <= interest) return false
                val reduction = payment - interest
                if (reduction >= remaining) return true
                remaining -= reduction
            }
            return remaining == 0L
        }
        require(settles(high)) { "Payment exceeds supported monetary range" }
        while (low < high) {
            val midpoint = low + (high - low) / 2
            if (settles(midpoint)) high = midpoint else low = midpoint + 1
        }
        return low
    }

    private fun ratioBasisPoints(numerator: Long, denominator: Long): Int {
        val whole = numerator / denominator
        if (whole > Int.MAX_VALUE / 10_000) return Int.MAX_VALUE
        // Long division of four decimal digits avoids overflowing remainder * 10,000.
        var remainder = numerator % denominator
        var fraction = 0
        repeat(4) {
            var digit = 0
            var next = 0L
            repeat(10) {
                if (next >= denominator - remainder) { next -= denominator - remainder; digit++ }
                else next += remainder
            }
            remainder = next
            fraction = fraction * 10 + digit
        }
        return (whole * 10_000 + fraction).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    private fun monthDistance(from: LocalDate, to: LocalDate): Int = checkedInt((to.year.toLong() - from.year) * 12 + to.month.ordinal - from.month.ordinal)
    private fun floorDiv(value: Int, divisor: Int): Int = value / divisor - if (value < 0 && value % divisor != 0) 1 else 0
    private fun checkedInt(value: Long): Int {
        require(value in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) { "Date interval is outside the supported range" }
        return value.toInt()
    }
    private fun checkSize(size: Int) { require(size <= 100_000) { "Too many occurrences; request a smaller window" } }
}
