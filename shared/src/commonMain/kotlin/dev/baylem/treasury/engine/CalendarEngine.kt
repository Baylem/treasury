package dev.baylem.treasury.engine

import dev.baylem.treasury.domain.*
import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class DateWindow(val start: LocalDate, val endInclusive: LocalDate) {
    init {
        require(endInclusive >= start) { "Window end precedes start" }
        require(endInclusive.toEpochDays() - start.toEpochDays() < 36_600) { "Calendar windows cannot exceed 100 years" }
    }
    operator fun contains(date: LocalDate): Boolean = date >= start && date <= endInclusive
}

@Serializable
sealed interface OccurrenceSource {
    @Serializable @SerialName("entry") data class Entry(val entryId: String, val originalDate: LocalDate) : OccurrenceSource
    @Serializable @SerialName("plan") data class Plan(val planId: String, val installmentNumber: Int) : OccurrenceSource
}

@Serializable
data class Occurrence(
    val id: String,
    val accountId: String,
    val title: String,
    val amountMinor: Long,
    val direction: EntryDirection,
    val date: LocalDate,
    val category: String,
    val source: OccurrenceSource,
    val isOverride: Boolean = false,
)

@Serializable
data class PlanPayment(
    val planId: String,
    val installmentNumber: Int,
    val date: LocalDate,
    val principalMinor: Long,
    val interestMinor: Long,
    val amountMinor: Long,
    val remainingPrincipalMinor: Long,
)

@Serializable
data class ForecastDay(
    val date: LocalDate,
    val openingBalanceMinor: Long,
    val incomeMinor: Long,
    val expenseMinor: Long,
    val balanceAdjustmentMinor: Long = 0,
    val closingBalanceMinor: Long,
)

@Serializable
data class Forecast(
    val window: DateWindow,
    val currency: String?,
    val openingBalanceMinor: Long,
    val days: List<ForecastDay>,
    val occurrences: List<Occurrence>,
) {
    val closingBalanceMinor: Long get() = days.lastOrNull()?.closingBalanceMinor ?: openingBalanceMinor
    val totalIncomeMinor: Long get() = days.fold(0L) { total, day -> Money.add(total, day.incomeMinor) }
    val totalExpenseMinor: Long get() = days.fold(0L) { total, day -> Money.add(total, day.expenseMinor) }
    val lowestBalanceMinor: Long get() = minOf(openingBalanceMinor, days.minOfOrNull { it.closingBalanceMinor } ?: openingBalanceMinor)
}

@Serializable enum class PayCadence { WEEKLY, BIWEEKLY, MONTHLY, SEMIMONTHLY }
@Serializable data class PayPeriod(val start: LocalDate, val endInclusive: LocalDate)
@Serializable enum class HealthStatus { HEALTHY, WATCH, AT_RISK }

@Serializable
data class FinancialHealth(
    val totalIncomeMinor: Long,
    val totalExpenseMinor: Long,
    val netCashFlowMinor: Long,
    val lowestBalanceMinor: Long,
    val negativeBalanceDays: Int,
    val firstNegativeDate: LocalDate?,
    /** Null when no income is scheduled; may be negative when expenses exceed income. */
    val savingsRateBasisPoints: Int?,
    val status: HealthStatus,
)

interface CalendarEngine {
    fun expand(snapshot: TreasurySnapshot, ownerId: String, window: DateWindow): List<Occurrence>
    fun schedulePlan(plan: PaymentPlan): List<PlanPayment>
    fun forecast(snapshot: TreasurySnapshot, ownerId: String, window: DateWindow, accountId: String? = null): Forecast
    /** Weekly/biweekly/monthly are anchored; semimonthly uses the 1st and 16th. */
    fun payPeriods(anchor: LocalDate, cadence: PayCadence, window: DateWindow): List<PayPeriod>
    fun health(forecast: Forecast): FinancialHealth
}
