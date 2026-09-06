package dev.baylem.treasury.domain

import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.*
import kotlin.time.Instant

class MoneyAndModelsTest {
    @Test fun decimalParsingPreservesExactCents() {
        assertEquals(123L, Money.parseMinor("1.23"))
        assertEquals(120L, Money.parseMinor("+1.2"))
        assertEquals(-1L, Money.parseMinor("-0.01"))
        assertEquals(100L, Money.parseMinor(" 0001 "))
        assertEquals(9007199254740993L, Money.parseMinor("90071992547409.93"))
        assertEquals(Long.MAX_VALUE, Money.parseMinor("92233720368547758.07"))
        assertEquals(Long.MIN_VALUE, Money.parseMinor("-92233720368547758.08"))
    }

    @Test fun invalidDecimalAmountsAreRejected() {
        for (value in listOf("", ".50", "1.", "1.234", "1e2", "1,000.00", "NaN", "--1", "1 000", "92233720368547758.08", "-92233720368547758.09"))
            assertFailsWith<IllegalArgumentException>(value) { Money.parseMinor(value) }
    }

    @Test fun moneyFormattingRoundTripsExtremes() {
        for (value in listOf(Long.MIN_VALUE, Long.MAX_VALUE, -100L, -1L, 0L, 1L, 99L, 100L, 10001L))
            assertEquals(value, Money.parseMinor(Money.formatMinor(value)))
        assertEquals("0.01", Money.formatMinor(1))
        assertEquals("-0.01", Money.formatMinor(-1))
    }

    @Test fun moneyArithmeticRejectsOverflow() {
        assertFailsWith<ArithmeticException> { Money.add(Long.MAX_VALUE, 1) }
        assertFailsWith<ArithmeticException> { Money.add(Long.MIN_VALUE, -1) }
        assertFailsWith<ArithmeticException> { Money.subtract(Long.MIN_VALUE, 1) }
        assertFailsWith<ArithmeticException> { Money.subtract(Long.MAX_VALUE, -1) }
        assertFailsWith<ArithmeticException> { Money.multiply(Long.MAX_VALUE, 2) }
        assertEquals(0L, Money.subtract(Long.MIN_VALUE, Long.MIN_VALUE))
        assertEquals(Long.MAX_VALUE, Money.subtract(-1, Long.MIN_VALUE))
    }

    @Test fun rationalMultiplicationRoundsHalfUp() {
        assertEquals(1L, Money.multiplyDivide(1, 1, 2))
        assertEquals(0L, Money.multiplyDivide(1, 1, 3))
        assertEquals(1L, Money.multiplyDivide(2, 1, 3))
        assertEquals(92233720368547758L, Money.multiplyDivide(Long.MAX_VALUE, 1, 100))
        assertEquals(Long.MAX_VALUE - 1, Money.multiplyDivide(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, Long.MAX_VALUE - 1))
        assertEquals(922337203685477581L, Money.multiplyDivide(Long.MAX_VALUE, 1_000_000_000, 10_000_000_000))
        assertEquals(Long.MAX_VALUE - 2, Money.multiplyDivide(Long.MAX_VALUE - 1, Long.MAX_VALUE - 1, Long.MAX_VALUE))
        assertFailsWith<ArithmeticException> { Money.multiplyDivide(Long.MAX_VALUE, Long.MAX_VALUE, 1) }
    }

    @Test fun recurrenceRulesRejectInvalidIntervalsAndCounts() {
        assertFailsWith<IllegalArgumentException> { RecurrenceRule(Frequency.DAILY, 0) }
        assertFailsWith<IllegalArgumentException> { RecurrenceEnd.Count(0) }
        assertFailsWith<IllegalArgumentException> { RecurrenceEnd.Count(100001) }
    }

    @Test fun syncMetadataRejectsInvalidChronology() {
        val instant = Instant.parse("2026-01-01T00:00:00Z")
        assertFailsWith<IllegalArgumentException> { EntityMeta("", "owner", instant, instant) }
        assertFailsWith<IllegalArgumentException> { EntityMeta("id", "owner", instant, instant, revision = 0) }
        assertFailsWith<IllegalArgumentException> { EntityMeta("id", "owner", instant, Instant.parse("2025-12-31T00:00:00Z")) }
        assertFailsWith<IllegalArgumentException> { EntityMeta("id", "owner", instant, instant, Instant.parse("2026-01-02T00:00:00Z")) }
    }

    @Test fun serializedSnapshotPreservesSealedRulesAndExactAmounts() {
        val instant = Instant.parse("2026-01-01T00:00:00Z")
        val date = LocalDate.parse("2026-01-01")
        val account = Account(EntityMeta("account", "owner", instant, instant), "Checking", balanceDate = date)
        val entry = FinancialEntry(EntityMeta("entry", "owner", instant, instant), account.id, "Income", 9007199254740993L, EntryDirection.INCOME, date,
            recurrence = RecurrenceRule(Frequency.MONTHLY, end = RecurrenceEnd.Count(12)))
        val exception = OccurrenceOverride(EntityMeta("override", "owner", instant, instant), entry.id, date, OverrideAction.Replace(date, 500, "Adjusted"))
        val snapshot = TreasurySnapshot(listOf(account), listOf(entry), overrides = listOf(exception))
        assertEquals(snapshot, Json.decodeFromString<TreasurySnapshot>(Json.encodeToString(snapshot)))
    }
}
