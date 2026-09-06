package dev.baylem.treasury.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.domain.*
import dev.baylem.treasury.engine.*
import dev.baylem.treasury.repository.Repository
import kotlinx.datetime.LocalDate

@Composable
internal fun PlansScreen(
    snapshot: TreasurySnapshot,
    account: Account,
    engine: CalendarEngine,
    today: LocalDate,
    onAdd: () -> Unit,
    onEdit: (PaymentPlan) -> Unit,
    onEntry: (FinancialEntry) -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Payment plans", style = MaterialTheme.typography.titleLarge)
        Button(onClick = onAdd) { Text("+ New plan") }
    }
    val plans = snapshot.plans.filter { it.accountId == account.id && it.deletedAt == null }.sortedBy { it.startDate }
    if (plans.isEmpty()) Panel(Modifier.fillMaxWidth()) {
        EmptyState(
            "Small steps, clearly mapped",
            "Split a purchase into installments or forecast a fixed-rate loan.",
            "Add a payment plan",
            onAdd
        )
    }
    plans.forEach { plan ->
        val schedule = remember(plan) {
            runCatching {
                engine.schedulePlan(plan).also { it.fold(0L) { sum, payment -> Money.add(sum, payment.amountMinor) } }
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(
                        when (plan.kind) {
                            PlanKind.BNPL -> "Buy now, pay later"; PlanKind.INSTALLMENT -> "Installments"; PlanKind.AMORTIZED -> "Fixed-rate loan · ${
                            Money.formatMinor(
                                plan.annualRateBasisPoints.toLong()
                            )
                        }% APR"
                        }
                    )
                    Text(plan.title, style = MaterialTheme.typography.titleLarge)
                }
                TextButton(onClick = { onEdit(plan) }) { Text("View & edit") }
            }
            schedule.getOrNull()?.let { payments ->
                val future = payments.filter { it.date >= today }
                val elapsed = payments.size - future.size
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Original principal"); Text(
                        money(
                            plan.principalMinor,
                            account.currency
                        ), style = MaterialTheme.typography.titleLarge
                    )
                    }
                    Column(Modifier.weight(1f)) {
                        Eyebrow("Upcoming payments"); Text(money(future.fold(0L) { total, payment ->
                        Money.add(
                            total,
                            payment.amountMinor
                        )
                    }, account.currency), style = MaterialTheme.typography.titleLarge)
                    }
                }
                LinearProgressIndicator(
                    progress = { elapsed.toFloat() / payments.size },
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    "$elapsed of ${payments.size} payment dates have passed. ${
                        future.firstOrNull()?.let {
                            "Next: ${it.date} · ${
                                money(
                                    it.amountMinor,
                                    account.currency
                                )
                            }"
                        } ?: "No future payments scheduled."
                    }", color = Muted)
                Text(
                    "Scheduled dates do not confirm that a payment was made.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Muted
                )
            }
            schedule.exceptionOrNull()?.let { Text(it.message ?: "Review this payment plan.", color = Expense) }
        }
    }
    Text("Recurring entries", style = MaterialTheme.typography.titleLarge)
    val recurring =
        snapshot.entries.filter { it.accountId == account.id && it.deletedAt == null && it.recurrence != null }
            .sortedBy { it.title }
    Panel(Modifier.fillMaxWidth()) {
        if (recurring.isEmpty()) Text(
            "Repeat an income or expense when you add it to your calendar. Subscriptions belong here, too.",
            color = Muted
        )
        recurring.forEach { entry ->
            val rule = requireNotNull(entry.recurrence)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${
                            money(
                                entry.amountMinor,
                                account.currency
                            )
                        } · Every ${rule.interval} ${
                            when (rule.frequency) {
                                Frequency.DAILY -> "day(s)"; Frequency.WEEKLY -> "week(s)"; Frequency.MONTHLY -> "month(s)"; Frequency.YEARLY -> "year(s)"
                            }
                        }", color = Muted
                    )
                }
                TextButton(onClick = { onEntry(entry) }) { Text("Edit series") }
            }
        }
    }
}

@Composable
internal fun InsightsScreen(health: FinancialHealth, forecast: Forecast, currency: String) {
    Panel(Modifier.fillMaxWidth()) {
        Eyebrow("Cash-flow outlook")
        Text(
            when (health.status) {
                HealthStatus.HEALTHY -> "Your scheduled balance stays above zero."; HealthStatus.WATCH -> "Keep an eye on this period."; HealthStatus.AT_RISK -> "A shortfall is on the calendar."
            },
            style = MaterialTheme.typography.headlineMedium,
            color = if (health.status == HealthStatus.AT_RISK) Expense else Pine
        )
        Text(
            if (health.firstNegativeDate != null) "Your first projected negative balance is ${health.firstNegativeDate}. Review upcoming expenses and income before that date." else "You have ${
                money(
                    health.lowestBalanceMinor,
                    currency
                )
            } at your lowest projected point. This reflects only the entries you’ve recorded.", color = Muted
        )
        ForecastChart(forecast, currency)
        HorizontalDivider()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Eyebrow("Net cash flow"); Text(
                money(health.netCashFlowMinor, currency),
                style = MaterialTheme.typography.titleLarge
            )
            }
            Column(Modifier.weight(1f)) {
                Eyebrow("Days below zero"); Text(
                health.negativeBalanceDays.toString(),
                style = MaterialTheme.typography.titleLarge
            )
            }
            Column(Modifier.weight(1f)) {
                Eyebrow("Income retained"); Text(health.savingsRateBasisPoints?.let {
                "${
                    Money.formatMinor(
                        it.toLong()
                    )
                }%"
            } ?: "—", style = MaterialTheme.typography.titleLarge)
            }
        }
        Text(
            "Income retained is scheduled income minus expenses, divided by income. It is unavailable when no income is scheduled.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Where your money is going", style = MaterialTheme.typography.titleLarge)
        val categories = forecast.occurrences.filter { it.direction == EntryDirection.EXPENSE }.groupBy { it.category }
            .mapValues { (_, entries) ->
                entries.fold(0L) { sum, entry ->
                    Money.add(
                        sum,
                        entry.amountMinor
                    )
                }
            }.entries.sortedByDescending { it.value }
        if (categories.isEmpty()) Text("Add expenses to see a breakdown by category.", color = Muted)
        categories.forEach { (category, amount) ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(category); Text(
                money(
                    amount,
                    currency
                )
            )
            }
            LinearProgressIndicator(
                progress = { if (forecast.totalExpenseMinor == 0L) 0f else (amount.toDouble() / forecast.totalExpenseMinor.toDouble()).toFloat() },
                modifier = Modifier.fillMaxWidth(),
                color = Pine
            )
        }
    }
}

@Composable
internal fun AccountsScreen(
    accounts: List<Account>,
    snapshot: TreasurySnapshot,
    engine: CalendarEngine,
    today: LocalDate,
    ownerId: String,
    onEdit: (Account) -> Unit
) {
    accounts.forEach { account ->
        val forecast = remember(snapshot, account, today) {
            runCatching {
                engine.forecast(
                    snapshot,
                    ownerId,
                    DateWindow(today, today),
                    account.id
                )
            }
        }
        Panel(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column(Modifier.weight(1f)) {
                    Eyebrow(account.currency); Text(
                    account.name,
                    style = MaterialTheme.typography.headlineMedium
                )
                }
                TextButton(onClick = { onEdit(account) }) { Text("Edit account") }
            }
            Eyebrow("Projected balance today")
            Text(forecast.getOrNull()?.closingBalanceMinor?.let { money(it, account.currency) } ?: "Unavailable",
                style = MaterialTheme.typography.headlineLarge)
            Text(
                "Opening balance: ${money(account.openingBalanceMinor, account.currency)} on ${account.balanceDate}.",
                color = Muted
            )
            Text(
                "${snapshot.entries.count { it.accountId == account.id && it.deletedAt == null }} entries · ${snapshot.plans.count { it.accountId == account.id && it.deletedAt == null }} payment plans",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
            forecast.exceptionOrNull()?.let { Text(it.message ?: "Review this account’s schedule.", color = Expense) }
        }
    }
}

@Composable
internal fun SettingsScreen(
    repository: Repository,
    anchor: String,
    onAnchor: (String) -> Unit,
    cadence: PayCadence,
    onCadence: (PayCadence) -> Unit,
    action: (String, suspend () -> Unit) -> Unit
) {
    var backup by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var showImport by rememberSaveable { mutableStateOf(false) }
    @Suppress("DEPRECATION") val clipboard = LocalClipboardManager.current
    ConnectionSettings()
    Panel(Modifier.fillMaxWidth()) {
        Text("Pay-period view", style = MaterialTheme.typography.titleLarge)
        Text(
            "Choose a known payday. The calendar builds periods around it, including earlier dates. Semi-monthly periods begin on the 1st and 16th.",
            color = Muted
        )
        Field(anchor, onAnchor, "Known payday · YYYY-MM-DD")
        if (runCatching { parseDate(anchor) }.isFailure) Text(
            "Enter a valid YYYY-MM-DD date. Until then, today is used.",
            color = Expense
        )
        ChoiceRow(PayCadence.entries, cadence, {
            when (it) {
                PayCadence.WEEKLY -> "Weekly"; PayCadence.BIWEEKLY -> "Every 2 weeks"; PayCadence.MONTHLY -> "Monthly"; PayCadence.SEMIMONTHLY -> "Twice a month"
            }
        }, onCadence)
        Text("View preferences apply to this session.", color = Muted, style = MaterialTheme.typography.bodySmall)
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("Your data, in your hands", style = MaterialTheme.typography.titleLarge)
        Text(
            "Export a full JSON backup to keep or move your records. Backups contain your financial details; store them somewhere private.",
            color = Muted
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = {
                action("Backup ready to copy.") {
                    backup = repository.exportBackup()
                }
            }) { Text("Prepare backup") }
            OutlinedButton(onClick = { showImport = !showImport }) { Text("Import backup") }
        }
        if (backup.isNotEmpty()) {
            SelectionContainer {
                OutlinedTextField(
                    backup,
                    {},
                    readOnly = true,
                    modifier = Modifier.fillMaxWidth().heightIn(max = 180.dp),
                    label = { Text("Backup JSON") })
            }
            Button(onClick = { clipboard.setText(AnnotatedString(backup)) }) { Text("Copy backup to clipboard") }
        }
        if (showImport) {
            Field(importText, { importText = it }, "Paste Treasury backup JSON", multiline = true)
            Text(
                "Import merges records and keeps the newest version. It does not remove records that are missing from the backup.",
                color = Muted,
                style = MaterialTheme.typography.bodySmall
            )
            Button(
                enabled = importText.isNotBlank(),
                onClick = {
                    action("Backup imported.") {
                        repository.importBackup(importText); importText = ""; showImport = false
                    }
                }) { Text("Import and merge") }
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Text("About Treasury", style = MaterialTheme.typography.titleLarge)
        Text(
            "A personal finance calendar for the things you plan. Income, expenses, subscriptions, and loans are entered manually. Treasury never connects to your bank.",
            color = Muted
        )
        Text(
            "Forecasts are projections, and scheduled payments do not confirm actual payment. Amounts use two decimal places; supported account currencies are USD, EUR, GBP, CAD, and AUD.",
            color = Muted,
            style = MaterialTheme.typography.bodySmall
        )
        if (repository.ownerId == "local") DeleteAction(
            "Erase local data",
            "Permanently erase this profile’s accounts, entries, plans, and deletion records from this device? This cannot be undone. Export a backup first if you need a copy.",
            { backup = ""; importText = "" }) { repository.eraseLocalData() }
    }
}
