package dev.baylem.treasury.ui

import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import dev.baylem.treasury.domain.*
import dev.baylem.treasury.engine.*
import dev.baylem.treasury.repository.*
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.*
import java.io.File
import java.util.UUID
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds

/** Exercises actual shared screens with the real repository and pure financial engine. */
@OptIn(ExperimentalTestApi::class)
class TreasuryUiTest {
    private val today get() = Clock.System.todayIn(TimeZone.currentSystemDefault())
    private fun uuid(label: String) = UUID.nameUUIDFromBytes("treasury-ui:$label".toByteArray(Charsets.UTF_8)).toString()

    private fun sample(): TreasurySnapshot {
        val instant = Clock.System.now()
        fun meta(id: String) = EntityMeta(uuid(id), "ui-test", instant, instant)
        val first = LocalDate(today.year, today.month, 1)
        val account = Account(meta("checking"), "Everyday checking", "USD", 247850, first)
        fun entry(id: String, title: String, amount: Long, day: Int, direction: EntryDirection = EntryDirection.EXPENSE,
                  category: String = "Other", frequency: Frequency = Frequency.MONTHLY) = FinancialEntry(
            meta(id), account.id, title, amount, direction, LocalDate(first.year, first.month, day), category,
            recurrence = RecurrenceRule(frequency))
        return TreasurySnapshot(listOf(account), listOf(
            entry("paycheck-a", "Paycheck", 325000, 2, EntryDirection.INCOME, "Salary"),
            entry("paycheck-b", "Paycheck", 325000, 16, EntryDirection.INCOME, "Salary"),
            entry("rent", "Rent", 145000, 1, category = "Housing"),
            entry("internet", "Internet", 6500, 12, category = "Utilities"),
            entry("streaming", "Streaming", 1599, 18, category = "Subscriptions"),
            entry("groceries", "Groceries", 8500, today.day, category = "Food", frequency = Frequency.WEEKLY),
            FinancialEntry(meta("coffee"), account.id, "Coffee", 525, EntryDirection.EXPENSE, today, "Food"),
        ), listOf(PaymentPlan(meta("desk"), account.id, "Home office desk", 48000,
            LocalDate(first.year, first.month, 8), 4, PlanKind.BNPL, paymentIntervalDays = 14)))
    }

    private fun withTreasury(width: Int = 1360, height: Int = 900, initial: TreasurySnapshot = sample(),
                             block: suspend DesktopComposeUiTest.(LocalRepository) -> Unit) {
        val store = MemoryDataStore()
        runBlocking { store.write("ui-test", initial) }
        val repository = LocalRepository("ui-test", store)
        runBlocking { repository.initialize() }
        try {
            runDesktopComposeUiTest(width, height, testTimeout = 45.seconds) {
                setContent {
                    val state by repository.state.collectAsState()
                    val snapshot = (state as? RepositoryState.Ready)?.snapshot ?: TreasurySnapshot()
                    TreasuryTheme { TreasuryHome(repository, DefaultCalendarEngine(), snapshot) }
                }
                waitForIdle()
                if (initial.accounts.isNotEmpty()) awaitForecast()
                block(repository)
            }
        } finally {
            runBlocking { repository.close() }
        }
    }

    private fun DesktopComposeUiTest.awaitForecast() {
        waitUntil(timeoutMillis = 10_000) { onAllNodesWithText("PROJECTED CLOSING").fetchSemanticsNodes().isNotEmpty() }
        waitForIdle()
    }

    private fun LocalRepository.snapshot() = (state.value as RepositoryState.Ready).snapshot

    private fun DesktopComposeUiTest.screenshot(name: String, width: Int, height: Int) {
        waitForIdle()
        val bitmap = captureToImage().toAwtImage()
        assertEquals(width, bitmap.width)
        assertEquals(height, bitmap.height)
        val output = File("build/qa/$name.png")
        check(output.parentFile.mkdirs() || output.parentFile.isDirectory)
        check(ImageIO.write(bitmap, "png", output))
    }

    @Test fun createAccountAndRecurringExpenseThroughRealForms() = withTreasury(initial = TreasurySnapshot()) { repository ->
        onNodeWithText("Create your first account").performClick()
        onNodeWithText("Save").performClick()
        onNodeWithText("Account name must contain 1–120 characters").assertExists()
        onNodeWithText("Account name").performTextReplacement("Checking")
        onNodeWithText("Opening balance").performTextReplacement("1250.45")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().accounts.size == 1 }
        awaitForecast()
        assertEquals(125045L, repository.snapshot().accounts.single().openingBalanceMinor)
        onNodeWithText("Checking · USD").assertExists()

        onNodeWithText("+  Add entry").performClick()
        onNodeWithText("Title").performTextReplacement("Music subscription")
        onNodeWithText("Amount · USD").performTextReplacement("15.99")
        onNodeWithText("Monthly").performScrollTo().performClick()
        onNodeWithText("Subscriptions").performScrollTo().performClick()
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().entries.size == 1 }
        awaitForecast()
        val saved = repository.snapshot().entries.single()
        assertEquals("Music subscription", saved.title)
        assertEquals(1599L, saved.amountMinor)
        assertEquals("Subscriptions", saved.category)
        assertEquals(Frequency.MONTHLY, saved.recurrence?.frequency)
        assertEquals(today, saved.date)
        screenshot("desktop-created-account", 1360, 900)
    }

    @Test fun desktopCalendarNavigatesAllFourViews() = withTreasury { _ ->
        screenshot("desktop-calendar", 1360, 900)
        onNodeWithContentDescription("Next Month").performClick()
        awaitForecast()
        onNodeWithText(monthLabel(today.plus(1, DateTimeUnit.MONTH))).assertExists()
        onNodeWithText("Today").performClick()
        onNodeWithText("Week", substring = false).performClick()
        awaitForecast()
        onNodeWithText("Search titles or categories").assertExists()
        onNodeWithText("Day", substring = false).performClick()
        awaitForecast()
        onNodeWithText("${today} — ${today}").assertExists()
        onNodeWithText("Pay period", substring = false).performClick()
        awaitForecast()
        val expected = DefaultCalendarEngine().payPeriods(today, PayCadence.BIWEEKLY, DateWindow(today, today)).single()
        onNodeWithText("${expected.start} — ${expected.endInclusive}").assertExists()
        onNodeWithContentDescription("Previous Pay period").performClick()
        awaitForecast()
        val prior = DefaultCalendarEngine().payPeriods(today, PayCadence.BIWEEKLY,
            DateWindow(expected.start.minus(1, DateTimeUnit.DAY), expected.start.minus(1, DateTimeUnit.DAY))).single()
        onNodeWithText("${prior.start} — ${prior.endInclusive}").assertExists()
    }

    @Test fun mobileCalendarAndEntryDialogRenderAtPhoneWidth() = withTreasury(390, 844) { _ ->
        screenshot("mobile-calendar-top", 390, 844)
        onNodeWithText("ON YOUR CALENDAR").performScrollTo()
        screenshot("mobile-calendar-grid", 390, 844)
        onNodeWithText("PROJECTED END OF DAY").performScrollTo()
        screenshot("mobile-calendar-agenda", 390, 844)
        onNodeWithText("+  Add entry").performScrollTo().performClick()
        onNodeWithText("Title").performTextReplacement("Mobile expense")
        onNodeWithText("Amount · USD").performTextReplacement("42.75")
        screenshot("mobile-entry-dialog", 390, 844)
        onNodeWithText("Cancel").performClick()
        onNodeWithText("Add an entry").assertDoesNotExist()
    }

    @Test fun oneTimeOccurrenceEditsOriginalEntry() = withTreasury { repository ->
        onNodeWithText("Coffee").performScrollTo().performClick()
        onNodeWithText("Edit entry").assertExists()
        onNodeWithText("Amount · USD").performTextReplacement("6.25")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().entries.first { it.id == uuid("coffee") }.amountMinor == 625L }
        assertTrue(repository.snapshot().overrides.isEmpty())
    }

    @Test fun recurringOccurrenceChangesOnlyOneInstance() = withTreasury { repository ->
        onNodeWithText("Groceries").performScrollTo().performClick()
        onNodeWithText("Change this occurrence").assertExists()
        onNodeWithText("Amount · USD").performTextReplacement("95.10")
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().overrides.size == 1 }
        val change = repository.snapshot().overrides.single()
        assertEquals(today, change.originalDate)
        assertEquals(9510L, (change.action as OverrideAction.Replace).amountMinor)
        assertEquals(8500L, repository.snapshot().entries.first { it.id == uuid("groceries") }.amountMinor)
    }

    @Test fun bnplPlanFormPersistsPreviewedSchedule() = withTreasury { repository ->
        onNodeWithText("Payment plans").performClick()
        onNodeWithText("+ New plan").performClick()
        onNodeWithText("Plan title").performTextReplacement("New laptop")
        onNodeWithText("Principal · USD").performTextReplacement("1000.01")
        onNodeWithText("Number of payments").performTextReplacement("4")
        onNodeWithText("Total $1,000.01 · Interest $0.00").performScrollTo().assertExists()
        onAllNodesWithText("$250.00").onLast().performScrollTo()
        screenshot("desktop-plan-preview", 1360, 900)
        onNodeWithText("Save").performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().plans.size == 2 }
        val plan = repository.snapshot().plans.first { it.title == "New laptop" }
        assertEquals(14, plan.paymentIntervalDays)
        assertEquals(listOf(25001L, 25000L, 25000L, 25000L), DefaultCalendarEngine().schedulePlan(plan).map { it.amountMinor })
    }

    @Test fun mobileInsightsAndAccountsRemainUsable() = withTreasury(390, 844) { _ ->
        onNodeWithText("Insights", substring = false).performClick()
        onNodeWithText("Income retained is scheduled income minus expenses, divided by income. It is unavailable when no income is scheduled.").performScrollTo()
        screenshot("mobile-insights", 390, 844)
        onNodeWithText("treasury.").performScrollTo()
        onNodeWithText("Accounts", substring = false).performScrollTo().performClick()
        onNodeWithText("Edit account").assertExists()
        onNodeWithText("Opening balance:", substring = true).performScrollTo()
        screenshot("mobile-accounts", 390, 844)
    }

    @Test fun accountDeletionCanBeCancelledAndThenSoftDeletesDependents() = withTreasury { repository ->
        onNodeWithText("Accounts", substring = false).performClick()
        onNodeWithText("Edit account").performClick()
        onNodeWithText("Delete account").performScrollTo().performClick()
        onNodeWithText("Keep it").performClick()
        assertTrue(repository.snapshot().accounts.all { it.deletedAt == null })
        onNodeWithText("Delete account").performClick()
        onAllNodes(hasText("Delete account") and hasClickAction()).onLast().performClick()
        waitUntil(timeoutMillis = 10_000) { repository.snapshot().accounts.all { it.deletedAt != null } }
        assertTrue(repository.snapshot().entries.all { it.deletedAt != null })
        assertTrue(repository.snapshot().plans.all { it.deletedAt != null })
        onNodeWithText("Create your first account").assertExists()
        assertEquals(1, repository.snapshot().accounts.size)
    }

    @Test fun invalidEntryAmountStaysInFormWithoutMutatingRepository() = withTreasury { repository ->
        val before = repository.snapshot()
        onNodeWithText("+  Add entry").performClick()
        onNodeWithText("Title").performTextReplacement("Invalid amount")
        onNodeWithText("Amount · USD").performTextReplacement("0.009")
        onNodeWithText("Save").performClick()
        onNodeWithText("Enter a decimal amount with at most two places").assertExists()
        onNodeWithText("Add an entry").assertExists()
        assertEquals(before, repository.snapshot())
    }
}
