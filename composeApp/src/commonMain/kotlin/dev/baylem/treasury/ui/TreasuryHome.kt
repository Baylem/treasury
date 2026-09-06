package dev.baylem.treasury.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.domain.*
import dev.baylem.treasury.engine.*
import dev.baylem.treasury.repository.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.*
import kotlin.time.Clock

internal enum class Destination(val label: String, val glyph: String) {
    CALENDAR(
        "Calendar",
        "▦"
    ),
    PLANS("Payment plans", "◷"), INSIGHTS("Insights", "↗"), ACCOUNTS("Accounts", "▤"), SETTINGS("Settings", "⚙")
}

internal enum class CalendarView(val label: String) { DAY("Day"), WEEK("Week"), MONTH("Month"), PAY_PERIOD("Pay period") }
internal sealed interface Editor {
    data class AccountForm(val value: Account? = null) : Editor
    data class EntryForm(val value: FinancialEntry? = null) : Editor
    data class PlanForm(val value: PaymentPlan? = null) : Editor
    data class Instance(val value: Occurrence) : Editor
}

internal fun calendarWindow(
    date: LocalDate,
    view: CalendarView,
    anchor: LocalDate,
    cadence: PayCadence,
    engine: CalendarEngine
): DateWindow = when (view) {
    CalendarView.DAY -> DateWindow(date, date)
    CalendarView.WEEK -> {
        val start = date.minus(date.dayOfWeek.ordinal, DateTimeUnit.DAY); DateWindow(
            start,
            start.plus(6, DateTimeUnit.DAY)
        )
    }

    CalendarView.MONTH -> {
        val start = LocalDate(date.year, date.month, 1); DateWindow(
            start,
            start.plus(1, DateTimeUnit.MONTH).minus(1, DateTimeUnit.DAY)
        )
    }

    CalendarView.PAY_PERIOD -> engine.payPeriods(anchor, cadence, DateWindow(date, date)).first()
        .let { DateWindow(it.start, it.endInclusive) }
}

@Composable
fun TreasuryHome(repository: Repository, engine: CalendarEngine, snapshot: TreasurySnapshot) {
    val today = Clock.System.todayIn(TimeZone.currentSystemDefault())
    var selectedDateText by rememberSaveable { mutableStateOf(today.toString()) }
    val selectedDate = LocalDate.parse(selectedDateText)
    var destination by rememberSaveable { mutableStateOf(Destination.CALENDAR) }
    var view by rememberSaveable { mutableStateOf(CalendarView.MONTH) }
    var accountId by rememberSaveable { mutableStateOf<String?>(null) }
    val accounts = snapshot.accounts.filter { it.deletedAt == null }
    val account = accounts.firstOrNull { it.id == accountId } ?: accounts.firstOrNull()
    var anchorText by rememberSaveable { mutableStateOf(today.toString()) }
    var cadence by rememberSaveable { mutableStateOf(PayCadence.BIWEEKLY) }
    val anchor = runCatching { LocalDate.parse(anchorText) }.getOrDefault(today)
    var editor by remember { mutableStateOf<Editor?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val window =
        remember(selectedDate, view, anchor, cadence) { calendarWindow(selectedDate, view, anchor, cadence, engine) }
    val forecastResult by produceState<Result<Forecast>?>(null, snapshot, account?.id, window) {
        value = null
        value = withContext(Dispatchers.Default) {
            runCatching {
                engine.forecast(
                    snapshot,
                    repository.ownerId,
                    window,
                    account?.id
                ).also { engine.health(it) }
            }
        }
    }
    val forecast = forecastResult?.getOrNull()
    val currency = account?.currency ?: "USD"
    fun action(message: String, operation: suspend () -> Unit) {
        scope.launch {
            try {
                operation(); snackbar.showSnackbar(message)
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error; snackbar.showSnackbar(
                    error.message ?: "The change could not be saved. Please try again."
                )
            }
        }
    }

    fun addEntry() {
        editor = if (account == null) Editor.AccountForm() else Editor.EntryForm()
    }
    Scaffold(containerColor = Paper, snackbarHost = { SnackbarHost(snackbar) }) { insets ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(insets).safeDrawingPadding()) {
            val wide = maxWidth >= 1100.dp
            Row(Modifier.fillMaxSize()) {
                if (wide) Sidebar(destination, { destination = it })
                Column(
                    Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                        .padding(if (wide) 32.dp else 16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    if (!wide) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("treasury.", color = Pine, style = MaterialTheme.typography.headlineMedium)
                            Text("MANUAL BY DESIGN", style = MaterialTheme.typography.labelSmall, color = Muted)
                        }
                        Row(
                            Modifier.horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Destination.entries.forEach { item ->
                                FilterChip(
                                    selected = destination == item,
                                    onClick = { destination = item },
                                    label = { Text(item.label) })
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Eyebrow("Your money, in perspective")
                            Text(
                                when (destination) {
                                    Destination.CALENDAR -> "A little clarity, every day."; Destination.PLANS -> "One payment at a time."; Destination.INSIGHTS -> "Look a little further ahead."; Destination.ACCOUNTS -> "A place for every balance."; Destination.SETTINGS -> "Make Treasury yours."
                                }, style = MaterialTheme.typography.headlineLarge
                            )
                        }
                        if (wide) Button(
                            onClick = ::addEntry,
                            contentPadding = PaddingValues(horizontal = 22.dp, vertical = 14.dp)
                        ) { Text("+  Add entry") }
                    }
                    if (accounts.isEmpty()) {
                        Panel(Modifier.fillMaxWidth()) {
                            Eyebrow("Welcome to Treasury")
                            Text(
                                "Know what’s coming.\nMake room for what matters.",
                                style = MaterialTheme.typography.displaySmall
                            )
                            Text(
                                "Bring your income, bills, subscriptions, and payment plans into one calm calendar. Start with a balance, then add what’s ahead.",
                                color = Muted
                            )
                            Text(
                                "Your entries stay on this device. No bank connection is needed.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = Muted
                            )
                            Button(onClick = { editor = Editor.AccountForm() }) { Text("Create your first account") }
                        }
                        if (destination == Destination.SETTINGS) SettingsScreen(
                            repository,
                            anchorText,
                            { anchorText = it },
                            cadence,
                            { cadence = it },
                            ::action
                        )
                    } else {
                        if (destination != Destination.SETTINGS) {
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                accounts.forEach { item ->
                                    FilterChip(
                                        selected = item.id == account?.id,
                                        onClick = { accountId = item.id },
                                        label = { Text("${item.name} · ${item.currency}") })
                                }
                                TextButton(onClick = { editor = Editor.AccountForm() }) { Text("+ Account") }
                            }
                        }
                        when (destination) {
                            Destination.CALENDAR, Destination.INSIGHTS -> {
                                CalendarToolbar(
                                    selectedDate,
                                    view,
                                    { view = it },
                                    { selectedDateText = it.toString() },
                                    window,
                                    today
                                )
                                if (forecastResult == null) LinearProgressIndicator(Modifier.fillMaxWidth())
                                forecastResult?.exceptionOrNull()?.let { error ->
                                    Panel {
                                        Text(
                                            "Forecast unavailable",
                                            style = MaterialTheme.typography.titleMedium
                                        ); Text(
                                        error.message ?: "Review this account’s dates and amounts.",
                                        color = Expense
                                    )
                                    }
                                }
                                if (forecast != null) {
                                    SummaryCards(forecast, currency, wide)
                                    if (destination == Destination.CALENDAR) {
                                        if (!wide) Button(
                                            onClick = ::addEntry,
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("+  Add entry") }
                                        if (view == CalendarView.MONTH) {
                                            if (wide) Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                                                MonthCalendar(
                                                    selectedDate,
                                                    today,
                                                    forecast,
                                                    currency,
                                                    { selectedDateText = it.toString() },
                                                    Modifier.weight(1f)
                                                )
                                                DayAgenda(
                                                    selectedDate,
                                                    forecast,
                                                    currency,
                                                    { editor = Editor.Instance(it) },
                                                    ::addEntry,
                                                    Modifier.width(330.dp)
                                                )
                                            } else {
                                                MonthCalendar(
                                                    selectedDate,
                                                    today,
                                                    forecast,
                                                    currency,
                                                    { selectedDateText = it.toString() })
                                                DayAgenda(
                                                    selectedDate,
                                                    forecast,
                                                    currency,
                                                    { editor = Editor.Instance(it) },
                                                    ::addEntry
                                                )
                                            }
                                        } else Agenda(forecast, currency) { editor = Editor.Instance(it) }
                                        Panel(Modifier.fillMaxWidth()) {
                                            Text(
                                                "The shape of this ${if (view == CalendarView.MONTH) "month" else "period"}",
                                                style = MaterialTheme.typography.titleMedium
                                            ); ForecastChart(forecast, currency)
                                        }
                                    } else InsightsScreen(engine.health(forecast), forecast, currency)
                                }
                            }

                            Destination.PLANS -> PlansScreen(
                                snapshot,
                                account!!,
                                engine,
                                today,
                                { editor = Editor.PlanForm() },
                                { editor = Editor.PlanForm(it) },
                                { editor = Editor.EntryForm(it) })

                            Destination.ACCOUNTS -> AccountsScreen(
                                accounts,
                                snapshot,
                                engine,
                                today,
                                repository.ownerId,
                                { editor = Editor.AccountForm(it) })

                            Destination.SETTINGS -> SettingsScreen(
                                repository,
                                anchorText,
                                { anchorText = it },
                                cadence,
                                { cadence = it },
                                ::action
                            )
                        }
                    }
                    Text(
                        "Forecasts reflect the entries you add. Update your calendar as plans change.",
                        color = Muted,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
    when (val current = editor) {
        null -> Unit
        is Editor.AccountForm -> AccountEditor(current.value, repository, today, { editor = null })
        is Editor.EntryForm -> EntryEditor(
            current.value,
            repository,
            accounts,
            account?.id,
            selectedDate,
            { editor = null })

        is Editor.PlanForm -> PlanEditor(
            current.value,
            repository,
            accounts,
            account?.id,
            selectedDate,
            engine,
            { editor = null })

        is Editor.Instance -> OccurrenceEditor(
            current.value,
            snapshot,
            repository,
            currency,
            { editor = null },
            { entry -> editor = Editor.EntryForm(entry) },
            { plan -> editor = Editor.PlanForm(plan) })
    }
}

@Composable
private fun Sidebar(selected: Destination, onSelect: (Destination) -> Unit) {
    Column(
        Modifier.width(220.dp).fillMaxHeight().background(Pine).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            "treasury.",
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            modifier = Modifier.padding(top = 16.dp)
        )
        Text("A CLEARER TOMORROW", color = Color(0xFFB3CABB), style = MaterialTheme.typography.labelSmall)
        Spacer(Modifier.height(40.dp))
        Destination.entries.forEach { item ->
            Row(
                Modifier.fillMaxWidth().background(
                    if (item == selected) Color.White.copy(alpha = .12f) else Color.Transparent,
                    RoundedCornerShape(12.dp)
                ).clickable { onSelect(item) }.padding(vertical = 14.dp, horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(item.glyph, color = Color(0xFFC3D5C7), style = MaterialTheme.typography.titleLarge)
                Text(
                    item.label,
                    color = Color.White,
                    fontWeight = if (item == selected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
        Spacer(Modifier.weight(1f))
        HorizontalDivider(color = Color.White.copy(alpha = .15f))
        Text("Yours to plan.", style = MaterialTheme.typography.titleLarge, color = Color(0xFFDFE8D9))
        Text(
            "Stored on your device.\nAlways manually entered.",
            color = Color(0xFFB3CABB),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun CalendarToolbar(
    date: LocalDate,
    view: CalendarView,
    onView: (CalendarView) -> Unit,
    onDate: (LocalDate) -> Unit,
    window: DateWindow,
    today: LocalDate
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (view == CalendarView.MONTH) monthLabel(date) else "${window.start} — ${window.endInclusive}",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = { onDate(today) }) { Text("Today") }
            IconButton(onClick = {
                onDate(
                    if (view == CalendarView.MONTH) date.minus(
                        1,
                        DateTimeUnit.MONTH
                    ) else window.start.minus(1, DateTimeUnit.DAY)
                )
            }, modifier = Modifier.semantics { contentDescription = "Previous ${view.label}" }) {
                Text(
                    "‹",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
            IconButton(onClick = {
                onDate(
                    if (view == CalendarView.MONTH) date.plus(
                        1,
                        DateTimeUnit.MONTH
                    ) else window.endInclusive.plus(1, DateTimeUnit.DAY)
                )
            }, modifier = Modifier.semantics { contentDescription = "Next ${view.label}" }) {
                Text(
                    "›",
                    style = MaterialTheme.typography.headlineMedium
                )
            }
        }
        ChoiceRow(CalendarView.entries, view, { it.label }, onView)
    }
}

@Composable
private fun SummaryCards(forecast: Forecast, currency: String, wide: Boolean) {
    val cards = listOf(
        Triple("Projected closing", forecast.closingBalanceMinor, Pine),
        Triple("Money in", forecast.totalIncomeMinor, Income),
        Triple("Money out", forecast.totalExpenseMinor, Expense),
        Triple("Lowest balance", forecast.lowestBalanceMinor, if (forecast.lowestBalanceMinor < 0) Expense else Pine)
    )

    @Composable
    fun Card(index: Int, modifier: Modifier) {
        val (title, amount, color) = cards[index]
        Panel(modifier) {
            Eyebrow(title); Text(
            money(amount, currency),
            style = MaterialTheme.typography.titleLarge,
            color = color
        ); Text(
            if (index == 0) "At the end of this period" else if (index == 3) "Your smallest cash buffer" else "Scheduled this period",
            style = MaterialTheme.typography.bodySmall,
            color = Muted
        )
        }
    }
    if (wide) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        cards.indices.forEach {
            Card(
                it,
                Modifier.weight(1f)
            )
        }
    }
    else Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        repeat(2) { row ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(
                    12.dp
                )
            ) { repeat(2) { column -> Card(row * 2 + column, Modifier.weight(1f)) } }
        }
    }
}

@Composable
private fun MonthCalendar(
    selected: LocalDate,
    today: LocalDate,
    forecast: Forecast,
    currency: String,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val start = forecast.window.start.minus(forecast.window.start.dayOfWeek.ordinal, DateTimeUnit.DAY)
    val days = forecast.occurrences.groupBy { it.date }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val compact = maxWidth < 550.dp
        Panel(Modifier.fillMaxWidth()) {
            Row {
                listOf("M", "T", "W", "T", "F", "S", "S").forEach {
                    Text(
                        it,
                        Modifier.weight(1f),
                        color = Muted,
                        style = MaterialTheme.typography.labelSmall
                    )
                }
            }
            val weeks =
                (forecast.window.start.dayOfWeek.ordinal + forecast.window.start.daysUntil(forecast.window.endInclusive) + 7) / 7
            repeat(weeks) { week ->
                Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(7) { weekday ->
                        val date = start.plus(week * 7 + weekday, DateTimeUnit.DAY)
                        val entries = days[date].orEmpty()
                        val inMonth = date.month == selected.month
                        Column(
                            Modifier.weight(1f).heightIn(min = if (compact) 62.dp else 86.dp).background(
                                if (date == selected) Color(0xFFE8EFE6) else Color.Transparent,
                                RoundedCornerShape(9.dp)
                            ).clickable { onSelect(date) }.padding(6.dp).semantics {
                                contentDescription =
                                    "$date, ${entries.size} entries${if (date == selected) ", selected" else ""}"
                            }, verticalArrangement = Arrangement.spacedBy(5.dp)
                        ) {
                            Text(
                                date.day.toString(),
                                style = MaterialTheme.typography.labelLarge,
                                color = if (!inMonth) Color(0xFFA4AFA5) else Pine,
                                fontWeight = if (date == today || date == selected) FontWeight.Bold else FontWeight.Normal
                            )
                            if (date == today) Box(Modifier.size(4.dp).background(Pine, RoundedCornerShape(4.dp)))
                            if (compact) EntryDirection.entries.forEach { direction ->
                                val count = entries.count { it.direction == direction }
                                if (count > 0) Text(
                                    "● $count",
                                    color = if (direction == EntryDirection.INCOME) Income else Expense,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1
                                )
                            } else entries.take(2).forEach { entry ->
                                val color = if (entry.direction == EntryDirection.INCOME) Income else Expense
                                Text(
                                    (if (entry.direction == EntryDirection.INCOME) "+" else "−") + money(
                                        entry.amountMinor,
                                        currency
                                    ),
                                    color = color,
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            if (!compact && entries.size > 2) Text(
                                "+${entries.size - 2} more",
                                color = Muted,
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
                if (week != weeks - 1) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .6f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    "↙ Income",
                    color = Income,
                    style = MaterialTheme.typography.labelSmall
                ); Text("↗ Expense", color = Expense, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun DayAgenda(
    date: LocalDate,
    forecast: Forecast,
    currency: String,
    onEntry: (Occurrence) -> Unit,
    onAdd: () -> Unit,
    modifier: Modifier = Modifier
) {
    Panel(modifier.fillMaxWidth()) {
        Eyebrow("On your calendar")
        Text(dayLabel(date), style = MaterialTheme.typography.titleLarge)
        val items = forecast.occurrences.filter { it.date == date }
        if (items.isEmpty()) EmptyState(
            "A little breathing room",
            "Nothing scheduled for this day.",
            "Add entry",
            onAdd
        )
        else items.forEach { OccurrenceRow(it, currency) { onEntry(it) } }
        forecast.days.firstOrNull { it.date == date }?.let { day ->
            HorizontalDivider(); Eyebrow("Projected end of day"); Text(
            money(
                day.closingBalanceMinor,
                currency
            ),
            color = if (day.closingBalanceMinor < 0) Expense else Pine,
            style = MaterialTheme.typography.headlineMedium
        )
        }
    }
}

@Composable
private fun Agenda(forecast: Forecast, currency: String, onEntry: (Occurrence) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    Panel(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            query,
            { query = it },
            label = { Text("Search titles or categories") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        val items = forecast.occurrences.filter { it.title.contains(query, true) || it.category.contains(query, true) }
        if (items.isEmpty()) EmptyState("Nothing scheduled", "Add an entry or choose another period.")
        items.groupBy { it.date }.forEach { (date, entries) ->
            Eyebrow(dayLabel(date)); entries.forEach {
            OccurrenceRow(
                it,
                currency
            ) { onEntry(it) }
        }; HorizontalDivider()
        }
    }
}
