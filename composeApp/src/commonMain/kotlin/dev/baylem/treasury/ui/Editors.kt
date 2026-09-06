package dev.baylem.treasury.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.baylem.treasury.domain.*
import dev.baylem.treasury.engine.*
import dev.baylem.treasury.repository.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate

@Composable
internal fun EditorDialog(
    title: String,
    onDismiss: () -> Unit,
    onSave: suspend () -> Unit,
    saveLabel: String = "Save",
    closeAfterSave: () -> Boolean = { true },
    content: @Composable ColumnScope.() -> Unit
) {
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    Dialog(
        onDismissRequest = { if (!busy) onDismiss() },
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)
    ) {
        Surface(
            Modifier.padding(16.dp).widthIn(max = 580.dp).fillMaxWidth().heightIn(max = 820.dp),
            shape = MaterialTheme.shapes.large
        ) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(title, style = MaterialTheme.typography.headlineMedium)
                Column(
                    Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    content = content
                )
                if (error != null) Text(
                    error!!,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
                    Spacer(Modifier.width(8.dp))
                    Button(enabled = !busy, onClick = {
                        busy = true; error = null
                        scope.launch {
                            try {
                                onSave(); if (closeAfterSave()) onDismiss()
                            } catch (failure: Exception) {
                                if (failure is CancellationException) throw failure; error =
                                    failure.message ?: "This change could not be saved."
                            } finally {
                                busy = false
                            }
                        }
                    }) {
                        if (busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) else Text(
                            saveLabel
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    numeric: Boolean = false,
    supporting: String? = null,
    multiline: Boolean = false
) {
    OutlinedTextField(
        value,
        onChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = !multiline,
        minLines = if (multiline) 2 else 1,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text),
        supportingText = supporting?.let { { Text(it) } })
}

@Composable
internal fun DeleteAction(label: String, detail: String, onDeleted: () -> Unit, delete: suspend () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    TextButton(onClick = { confirm = true }, colors = ButtonDefaults.textButtonColors(contentColor = Expense)) {
        Text(
            label
        )
    }
    if (confirm) AlertDialog(
        onDismissRequest = { if (!busy) confirm = false },
        title = { Text(label) },
        text = { Text(error ?: detail) },
        confirmButton = {
            Button(enabled = !busy, colors = ButtonDefaults.buttonColors(containerColor = Expense), onClick = {
                busy = true
                scope.launch {
                    try {
                        delete(); confirm = false; onDeleted()
                    } catch (failure: Exception) {
                        if (failure is CancellationException) throw failure; error =
                            failure.message ?: "The change could not be saved."
                    } finally {
                        busy = false
                    }
                }
            }) { Text(if (busy) "Working…" else label) }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = { confirm = false }) { Text("Keep it") } })
}

@Composable
internal fun AccountEditor(existing: Account?, repository: Repository, today: LocalDate, onDismiss: () -> Unit) {
    val meta = remember { existing?.meta ?: repository.createEntityMeta() }
    var name by rememberSaveable { mutableStateOf(existing?.name ?: "") }
    var balance by rememberSaveable { mutableStateOf(Money.formatMinor(existing?.openingBalanceMinor ?: 0)) }
    var date by rememberSaveable { mutableStateOf((existing?.balanceDate ?: today).toString()) }
    var currency by rememberSaveable { mutableStateOf(existing?.currency ?: "USD") }
    EditorDialog(if (existing == null) "Create an account" else "Edit account", onDismiss, {
        repository.saveAccount(Account(meta, name.trim(), currency, Money.parseMinor(balance), parseDate(date)))
    }) {
        Text("An account is a balance you track yourself, such as checking, savings, or cash.", color = Muted)
        Field(name, { name = it }, "Account name")
        Eyebrow("Currency")
        ChoiceRow(listOf("USD", "EUR", "GBP", "CAD", "AUD"), currency, { it }, { currency = it })
        Field(balance, { balance = it }, "Opening balance", numeric = true)
        Field(
            date,
            { date = it },
            "Balance date · YYYY-MM-DD",
            supporting = "The balance before any entries on this date. Earlier entries are excluded from this account’s forecast."
        )
        if (existing != null) DeleteAction(
            "Delete account",
            "This removes the account and its entries and plans from your calendar. Export a backup first if you may need them later.",
            onDismiss
        ) { repository.softDelete(EntityKind.ACCOUNT, existing.id) }
    }
}

internal fun parseDate(text: String): LocalDate = try {
    val date = LocalDate.parse(text.trim())
    require(date.year in 1900..2200) { "Choose a date between 1900 and 2200." }
    date
} catch (failure: IllegalArgumentException) {
    throw IllegalArgumentException("Enter a valid date as YYYY-MM-DD, between 1900 and 2200.", failure)
}

@Composable
private fun AccountChoice(accounts: List<Account>, selected: String, onSelect: (String) -> Unit) {
    Eyebrow("Account")
    ChoiceRow(accounts.map { it.id }, selected, { id -> accounts.first { it.id == id }.name }, onSelect)
}

@Composable
internal fun EntryEditor(
    existing: FinancialEntry?,
    repository: Repository,
    accounts: List<Account>,
    selectedAccountId: String?,
    date: LocalDate,
    onDismiss: () -> Unit
) {
    val meta = remember { existing?.meta ?: repository.createEntityMeta() }
    var accountId by rememberSaveable {
        mutableStateOf(
            existing?.accountId ?: selectedAccountId ?: accounts.first().id
        )
    }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.amountMinor?.let(Money::formatMinor) ?: "") }
    var direction by rememberSaveable { mutableStateOf(existing?.direction ?: EntryDirection.EXPENSE) }
    var scheduledDate by rememberSaveable { mutableStateOf((existing?.date ?: date).toString()) }
    var category by rememberSaveable { mutableStateOf(existing?.category ?: "Other") }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    var frequency by rememberSaveable { mutableStateOf(existing?.recurrence?.frequency?.name ?: "ONCE") }
    var interval by rememberSaveable { mutableStateOf((existing?.recurrence?.interval ?: 1).toString()) }
    var endMode by rememberSaveable {
        mutableStateOf(
            when (existing?.recurrence?.end) {
                null, RecurrenceEnd.Never -> "Never"; is RecurrenceEnd.Count -> "After a count"; is RecurrenceEnd.Until -> "On a date"
            }
        )
    }
    var until by rememberSaveable {
        mutableStateOf(
            (existing?.recurrence?.end as? RecurrenceEnd.Until)?.date?.toString() ?: date.toString()
        )
    }
    var count by rememberSaveable {
        mutableStateOf(
            ((existing?.recurrence?.end as? RecurrenceEnd.Count)?.count ?: 12).toString()
        )
    }
    EditorDialog(
        if (existing == null) "Add an entry" else if (existing.recurrence != null) "Edit recurring series" else "Edit entry",
        onDismiss,
        {
            val rule = if (frequency == "ONCE") null else RecurrenceRule(
                Frequency.valueOf(frequency),
                interval.toIntOrNull() ?: error("Enter a whole-number interval."),
                when (endMode) {
                    "After a count" -> RecurrenceEnd.Count(
                        count.toIntOrNull() ?: error("Enter a whole-number occurrence count.")
                    )

                    "On a date" -> RecurrenceEnd.Until(parseDate(until))
                    else -> RecurrenceEnd.Never
                }
            )
            val minor = Money.parseMinor(amount)
            require(minor > 0) { "Enter an amount greater than zero." }
            repository.saveEntry(
                FinancialEntry(
                    meta,
                    accountId,
                    title.trim(),
                    minor,
                    direction,
                    parseDate(scheduledDate),
                    category.trim(),
                    notes.trim(),
                    rule
                )
            )
        }) {
        ChoiceRow(
            EntryDirection.entries,
            direction,
            { if (it == EntryDirection.INCOME) "↙  Income" else "↗  Expense" },
            { direction = it })
        Field(title, { title = it }, "Title", supporting = "For example: Paycheck, Rent, or Netflix")
        Field(amount, { amount = it }, "Amount · ${accounts.first { it.id == accountId }.currency}", numeric = true)
        Field(
            scheduledDate,
            { scheduledDate = it },
            "${if (frequency == "ONCE") "Date" else "First date"} · YYYY-MM-DD"
        )
        AccountChoice(accounts, accountId) { accountId = it }
        Field(category, { category = it }, "Category")
        ChoiceRow(
            listOf("Housing", "Food", "Transport", "Subscriptions", "Salary", "Other"),
            category,
            { it },
            { category = it })
        Eyebrow("Repeat")
        ChoiceRow(
            listOf("ONCE") + Frequency.entries.map { it.name },
            frequency,
            { it.lowercase().replaceFirstChar(Char::uppercase) },
            { frequency = it })
        if (frequency != "ONCE") {
            Field(
                interval, { interval = it }, "Every how many ${
                    when (frequency) {
                        "DAILY" -> "days"; "WEEKLY" -> "weeks"; "MONTHLY" -> "months"; else -> "years"
                    }
                }?", true
            )
            ChoiceRow(listOf("Never", "After a count", "On a date"), endMode, { it }, { endMode = it })
            if (endMode == "After a count") Field(count, { count = it }, "Total occurrences, including the first", true)
            if (endMode == "On a date") Field(until, { until = it }, "Last date · YYYY-MM-DD")
            Text(
                "Dates at the end of a month use the last available day in shorter months.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
        }
        Field(notes, { notes = it }, "Notes · optional", multiline = true)
        if (existing != null) {
            SeriesChanges(existing, repository)
            DeleteAction(
                if (existing.recurrence != null) "Delete entire series" else "Delete entry",
                "This removes ${existing.title} from your calendar, including its occurrence changes.",
                onDismiss
            ) { repository.softDelete(EntityKind.ENTRY, existing.id) }
        }
    }
}

@Composable
private fun SeriesChanges(entry: FinancialEntry, repository: Repository) {
    val state by repository.state.collectAsState()
    val changes = (state as? RepositoryState.Ready)?.snapshot?.overrides.orEmpty()
        .filter { it.entryId == entry.id && it.deletedAt == null }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    if (changes.isNotEmpty()) {
        Eyebrow("Individual occurrence changes")
        changes.sortedBy { it.originalDate }.forEach { change ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(
                    "${change.originalDate} · ${
                        when (change.action) {
                            OverrideAction.Skip -> "Skipped"; is OverrideAction.Replace -> "Changed"
                        }
                    }", modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    scope.launch {
                        try {
                            repository.softDelete(EntityKind.OVERRIDE, change.id)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e; error = e.message
                        }
                    }
                }) { Text("Restore") }
            }
        }
    }
    error?.let { Text(it, color = Expense) }
}

@Composable
internal fun PlanEditor(
    existing: PaymentPlan?,
    repository: Repository,
    accounts: List<Account>,
    selectedAccountId: String?,
    date: LocalDate,
    engine: CalendarEngine,
    onDismiss: () -> Unit
) {
    val meta = remember { existing?.meta ?: repository.createEntityMeta() }
    var accountId by rememberSaveable {
        mutableStateOf(
            existing?.accountId ?: selectedAccountId ?: accounts.first().id
        )
    }
    var title by rememberSaveable { mutableStateOf(existing?.title ?: "") }
    var amount by rememberSaveable { mutableStateOf(existing?.principalMinor?.let(Money::formatMinor) ?: "") }
    var kind by rememberSaveable { mutableStateOf(existing?.kind ?: PlanKind.BNPL) }
    var installments by rememberSaveable { mutableStateOf((existing?.installments ?: 4).toString()) }
    var rate by rememberSaveable { mutableStateOf(Money.formatMinor((existing?.annualRateBasisPoints ?: 0).toLong())) }
    var start by rememberSaveable { mutableStateOf((existing?.startDate ?: date).toString()) }
    var timing by rememberSaveable { mutableStateOf(if (existing == null || existing.paymentIntervalDays == 14) "Every 2 weeks" else if (existing.paymentIntervalDays == 7) "Weekly" else if (existing.paymentIntervalDays != null) "Custom days" else "Monthly") }
    var monthInterval by rememberSaveable { mutableStateOf((existing?.intervalMonths ?: 1).toString()) }
    var dayInterval by rememberSaveable { mutableStateOf((existing?.paymentIntervalDays ?: 14).toString()) }
    var notes by rememberSaveable { mutableStateOf(existing?.notes ?: "") }
    fun buildPlan(): PaymentPlan = PaymentPlan(
        meta,
        accountId,
        title.trim().ifBlank { "Payment plan" },
        Money.parseMinor(amount),
        parseDate(start),
        installments.toIntOrNull() ?: error("Enter a whole number of payments."),
        kind,
        annualRateBasisPoints = if (kind == PlanKind.AMORTIZED) Money.parseMinor(rate)
            .also { require(it in 0..100_000) { "Enter an APR from 0% to 1,000%." } }.toInt() else 0,
        intervalMonths = monthInterval.toIntOrNull() ?: error("Enter a whole-number month interval."),
        category = existing?.category ?: "Loans",
        notes = notes.trim(),
        paymentIntervalDays = when (timing) {
            "Weekly" -> 7; "Every 2 weeks" -> 14; "Custom days" -> dayInterval.toIntOrNull()
                ?: error("Enter a whole-number day interval."); else -> null
        }
    )

    val preview = remember(
        amount,
        kind,
        installments,
        rate,
        start,
        timing,
        monthInterval,
        dayInterval
    ) {
        runCatching {
            engine.schedulePlan(buildPlan())
                .also { it.fold(0L) { sum, payment -> Money.add(sum, payment.amountMinor) } }
        }
    }
    val currency = accounts.first { it.id == accountId }.currency
    EditorDialog(if (existing == null) "Add a payment plan" else "Edit payment plan", onDismiss, {
        require(title.isNotBlank()) { "Give this plan a title." }
        val plan = buildPlan(); engine.schedulePlan(plan)
        .fold(0L) { sum, payment -> Money.add(sum, payment.amountMinor) }; repository.savePlan(plan)
    }) {
        ChoiceRow(PlanKind.entries, kind, {
            when (it) {
                PlanKind.BNPL -> "Buy now, pay later"; PlanKind.INSTALLMENT -> "Installments"; PlanKind.AMORTIZED -> "Loan"
            }
        }, { kind = it; timing = if (it == PlanKind.BNPL) "Every 2 weeks" else "Monthly" })
        Field(title, { title = it }, "Plan title")
        Field(amount, { amount = it }, "Principal · $currency", true)
        Field(installments, { installments = it }, "Number of payments", true)
        if (kind == PlanKind.AMORTIZED) Field(
            rate,
            { rate = it },
            "Annual interest rate · %",
            true,
            "Fixed rate, rounded to cents each payment period. Lender fees are entered separately."
        )
        Field(start, { start = it }, "First payment date · YYYY-MM-DD")
        ChoiceRow(listOf("Monthly", "Every 2 weeks", "Weekly", "Custom days"), timing, { it }, { timing = it })
        if (timing == "Monthly") Field(monthInterval, { monthInterval = it }, "Every how many months?", true)
        if (timing == "Custom days") Field(dayInterval, { dayInterval = it }, "Days between payments", true)
        AccountChoice(accounts, accountId) { accountId = it }
        Field(notes, { notes = it }, "Notes · optional", multiline = true)
        preview.getOrNull()?.let { payments ->
            HorizontalDivider(); Eyebrow("Payment preview")
            val total = payments.fold(0L) { sum, payment -> Money.add(sum, payment.amountMinor) }
            Text(
                "Total ${money(total, currency)} · Interest ${
                    money(
                        Money.subtract(total, Money.parseMinor(amount)),
                        currency
                    )
                }", style = MaterialTheme.typography.titleMedium
            )
            payments.take(12).forEach { payment ->
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        "${payment.installmentNumber}.  ${payment.date}",
                        color = Muted
                    ); Text(money(payment.amountMinor, currency))
                }
            }
            if (payments.size > 12) Text(
                "Showing the first 12 of ${payments.size} payments. Final payment: ${payments.last().date}.",
                color = Muted
            )
        }
        if (amount.isNotBlank()) preview.exceptionOrNull()
            ?.let { Text(it.message ?: "Review the plan details.", color = Expense) }
        if (existing != null) DeleteAction(
            "Delete plan",
            "This removes all scheduled payments for ${existing.title}. Past balances will also be recalculated.",
            onDismiss
        ) { repository.softDelete(EntityKind.PLAN, existing.id) }
    }
}

@Composable
internal fun OccurrenceEditor(
    occurrence: Occurrence,
    snapshot: TreasurySnapshot,
    repository: Repository,
    currency: String,
    onDismiss: () -> Unit,
    onSeries: (FinancialEntry) -> Unit,
    onPlan: (PaymentPlan) -> Unit
) {
    when (val source = occurrence.source) {
        is OccurrenceSource.Plan -> AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(occurrence.title) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Payment ${source.installmentNumber} · ${occurrence.date}"); Text(
                    money(occurrence.amountMinor, currency),
                    style = MaterialTheme.typography.headlineMedium
                ); Text(
                    "This payment is calculated from your plan. Edit the plan to change its schedule.",
                    color = Muted
                )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    snapshot.plans.firstOrNull { it.id == source.planId }?.let(onPlan)
                }) { Text("View plan") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } })

        is OccurrenceSource.Entry -> {
            val entry = snapshot.entries.first { it.id == source.entryId }
            if (entry.recurrence == null) {
                EntryEditor(
                    entry,
                    repository,
                    snapshot.accounts.filter { it.deletedAt == null },
                    entry.accountId,
                    entry.date,
                    onDismiss
                )
                return
            }
            val existing =
                snapshot.overrides.firstOrNull { it.entryId == source.entryId && it.originalDate == source.originalDate && it.deletedAt == null }
            val meta = remember { existing?.meta ?: repository.createEntityMeta() }
            var title by rememberSaveable { mutableStateOf(occurrence.title) }
            var date by rememberSaveable { mutableStateOf(occurrence.date.toString()) }
            var amount by rememberSaveable { mutableStateOf(Money.formatMinor(occurrence.amountMinor)) }
            EditorDialog("Change this occurrence", onDismiss, {
                val minor = Money.parseMinor(amount); require(minor > 0) { "Enter an amount greater than zero." }
                repository.saveOverride(
                    OccurrenceOverride(
                        meta,
                        source.entryId,
                        source.originalDate,
                        OverrideAction.Replace(parseDate(date), minor, title.trim())
                    )
                )
            }) {
                Text("Changes apply only to the occurrence originally on ${source.originalDate}.", color = Muted)
                Field(title, { title = it }, "Title")
                Field(date, { date = it }, "Date · YYYY-MM-DD")
                Field(amount, { amount = it }, "Amount · $currency", true)
                TextButton(onClick = { onSeries(entry) }) { Text(if (entry.recurrence == null) "Edit original entry" else "Edit entire recurring series") }
                DeleteAction(
                    "Skip this occurrence",
                    "This occurrence will be removed from the forecast. You can restore it from the original entry’s occurrence changes.",
                    onDismiss
                ) {
                    repository.saveOverride(
                        OccurrenceOverride(
                            meta,
                            source.entryId,
                            source.originalDate,
                            OverrideAction.Skip
                        )
                    )
                }
            }
        }
    }
}
