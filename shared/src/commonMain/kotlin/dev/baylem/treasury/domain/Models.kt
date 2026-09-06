package dev.baylem.treasury.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/** Sync metadata is shared by every stored row; generated occurrences are never stored. */
@Serializable
data class EntityMeta(
    val id: String,
    val ownerId: String,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
    val revision: Long = 1,
) {
    init {
        require(id.isNotBlank() && id.length <= 128) { "An entity ID is required" }
        require(ownerId.isNotBlank() && ownerId.length <= 128) { "An owner ID is required" }
        require(revision >= 1) { "Revision must be positive" }
        require(updatedAt >= createdAt) { "Updated time precedes creation" }
        require(deletedAt == null || deletedAt >= createdAt && deletedAt <= updatedAt) { "Invalid deletion time" }
    }
}

interface SyncMeta {
    val meta: EntityMeta
    val id: String get() = meta.id
    val ownerId: String get() = meta.ownerId
    val createdAt: Instant get() = meta.createdAt
    val updatedAt: Instant get() = meta.updatedAt
    val deletedAt: Instant? get() = meta.deletedAt
    val revision: Long get() = meta.revision
}

@Serializable
data class Account(
    override val meta: EntityMeta,
    val name: String,
    val currency: String = "USD",
    val openingBalanceMinor: Long = 0,
    /** Opening balance is measured immediately before transactions on this day. */
    val balanceDate: LocalDate,
) : SyncMeta {
    init {
        require(name.isNotBlank() && name.length <= 120) { "Account name must contain 1–120 characters" }
        require(currency.length == 3 && currency.all { it in 'A'..'Z' }) { "Use a three-letter currency code" }
    }
}

@Serializable enum class EntryDirection { INCOME, EXPENSE }
@Serializable enum class Frequency { DAILY, WEEKLY, MONTHLY, YEARLY }

@Serializable
sealed interface RecurrenceEnd {
    @Serializable @SerialName("never") data object Never : RecurrenceEnd
    @Serializable @SerialName("until") data class Until(val date: LocalDate) : RecurrenceEnd
    @Serializable @SerialName("count") data class Count(val count: Int) : RecurrenceEnd {
        init { require(count in 1..100_000) { "Occurrence count must be between 1 and 100,000" } }
    }
}

@Serializable
data class RecurrenceRule(val frequency: Frequency, val interval: Int = 1, val end: RecurrenceEnd = RecurrenceEnd.Never) {
    init { require(interval in 1..1200) { "Recurrence interval must be between 1 and 1,200" } }
}

@Serializable
data class FinancialEntry(
    override val meta: EntityMeta,
    val accountId: String,
    val title: String,
    val amountMinor: Long,
    val direction: EntryDirection,
    val date: LocalDate,
    val category: String = "Other",
    val notes: String = "",
    val recurrence: RecurrenceRule? = null,
) : SyncMeta {
    init {
        require(accountId.isNotBlank()) { "An account is required" }
        require(title.isNotBlank() && title.length <= 200) { "Title must contain 1–200 characters" }
        require(amountMinor >= 0) { "Amount cannot be negative; use direction" }
        require(category.isNotBlank() && category.length <= 80) { "Category must contain 1–80 characters" }
        require(notes.length <= 10_000) { "Notes are too long" }
        when (val end = recurrence?.end) {
            null, RecurrenceEnd.Never -> Unit
            is RecurrenceEnd.Count -> Unit
            is RecurrenceEnd.Until -> require(end.date >= date) { "Recurrence end precedes start" }
        }
    }
}

@Serializable
sealed interface OverrideAction {
    @Serializable @SerialName("skip") data object Skip : OverrideAction
    @Serializable @SerialName("replace") data class Replace(
        val date: LocalDate,
        val amountMinor: Long,
        val title: String? = null,
    ) : OverrideAction {
        init {
            require(amountMinor >= 0) { "Replacement amount cannot be negative" }
            require(title == null || title.isNotBlank() && title.length <= 200) { "Invalid replacement title" }
        }
    }
}

@Serializable
data class OccurrenceOverride(
    override val meta: EntityMeta,
    val entryId: String,
    val originalDate: LocalDate,
    val action: OverrideAction,
) : SyncMeta {
    init { require(entryId.isNotBlank()) { "An entry is required" } }
}

@Serializable enum class PlanKind { BNPL, INSTALLMENT, AMORTIZED }

@Serializable
data class PaymentPlan(
    override val meta: EntityMeta,
    val accountId: String,
    val title: String,
    val principalMinor: Long,
    /** First repayment date; an amortized payment includes one full interval of interest. */
    val startDate: LocalDate,
    val installments: Int,
    val kind: PlanKind,
    /** APR in hundredths of a percent: 1,200 means 12.00%. */
    val annualRateBasisPoints: Int = 0,
    val intervalMonths: Int = 1,
    val category: String = "Loans",
    val notes: String = "",
    /** Set to 14 for common pay-in-four BNPL schedules; null uses calendar months. */
    val paymentIntervalDays: Int? = null,
) : SyncMeta {
    init {
        require(accountId.isNotBlank()) { "An account is required" }
        require(title.isNotBlank() && title.length <= 200) { "Title must contain 1–200 characters" }
        require(principalMinor > 0) { "Principal must be positive" }
        require(installments in 1..1200) { "Installments must be between 1 and 1,200" }
        require(annualRateBasisPoints in 0..100_000) { "APR is outside the supported range" }
        require(intervalMonths in 1..120) { "Month interval must be between 1 and 120" }
        require(paymentIntervalDays == null || paymentIntervalDays in 1..3660) { "Day interval must be between 1 and 3,660" }
        require(kind == PlanKind.AMORTIZED || annualRateBasisPoints == 0) { "Interest-bearing plans must use amortization" }
        require(category.isNotBlank() && category.length <= 80) { "Category must contain 1–80 characters" }
        require(notes.length <= 10_000) { "Notes are too long" }
    }
}

@Serializable
data class TreasurySnapshot(
    val accounts: List<Account> = emptyList(),
    val entries: List<FinancialEntry> = emptyList(),
    val plans: List<PaymentPlan> = emptyList(),
    val overrides: List<OccurrenceOverride> = emptyList(),
)

/** Money helpers never convert monetary values through binary floating point. */
object Money {
    fun add(left: Long, right: Long): Long {
        if (right > 0 && left > Long.MAX_VALUE - right || right < 0 && left < Long.MIN_VALUE - right)
            throw ArithmeticException("Money overflow")
        return left + right
    }

    fun subtract(left: Long, right: Long): Long {
        if (right > 0 && left < Long.MIN_VALUE + right || right < 0 && left > Long.MAX_VALUE + right)
            throw ArithmeticException("Money overflow")
        return left - right
    }

    fun multiply(left: Long, right: Long): Long {
        require(left >= 0 && right >= 0) { "Money multiplication requires nonnegative operands" }
        if (right != 0L && left > Long.MAX_VALUE / right) throw ArithmeticException("Money overflow")
        return left * right
    }

    /** Exact round-half-up multiplication by a nonnegative rational number. */
    fun multiplyDivide(amount: Long, numerator: Long, denominator: Long): Long {
        require(amount >= 0 && numerator >= 0 && denominator > 0)
        val whole = multiply(amount / denominator, numerator)
        val partial = amount % denominator
        val (fraction, remainder) = if (numerator == 0L || partial <= Long.MAX_VALUE / numerator) {
            val product = partial * numerator
            product / denominator to product % denominator
        } else {
            // Binary long division avoids an overflowing intermediate product even if the
            // final result fits. Quotient and remainder stay in range throughout.
            var quotient = 0L
            var rest = 0L
            for (bit in 62 downTo 0) {
                quotient = multiply(quotient, 2)
                if (rest >= denominator - rest) { rest -= denominator - rest; quotient = add(quotient, 1) }
                else rest *= 2
                if ((numerator ushr bit) and 1L != 0L) {
                    if (rest >= denominator - partial) { rest -= denominator - partial; quotient = add(quotient, 1) }
                    else rest += partial
                }
            }
            quotient to rest
        }
        return add(add(whole, fraction), if (remainder >= denominator / 2 + denominator % 2) 1 else 0)
    }

    /** Accepts a plain decimal with at most two places, without locale ambiguity. */
    fun parseMinor(text: String): Long {
        val input = text.trim()
        require(input.matches(Regex("[+-]?[0-9]+(\\.[0-9]{1,2})?"))) { "Enter a decimal amount with at most two places" }
        val negative = input.startsWith('-')
        val unsigned = input.removePrefix("-").removePrefix("+")
        val parts = unsigned.split('.')
        val fraction = parts.getOrNull(1)?.padEnd(2, '0') ?: "00"
        return ((if (negative) "-" else "") + parts[0].trimStart('0').ifEmpty { "0" } + fraction).toLongOrNull()
            ?: throw IllegalArgumentException("Amount is too large")
    }

    fun formatMinor(amount: Long): String {
        val raw = amount.toString().removePrefix("-").padStart(3, '0')
        return (if (amount < 0) "-" else "") + raw.dropLast(2) + "." + raw.takeLast(2)
    }
}
