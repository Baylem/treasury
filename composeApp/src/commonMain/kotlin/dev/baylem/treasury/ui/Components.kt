package dev.baylem.treasury.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.baylem.treasury.domain.EntryDirection
import dev.baylem.treasury.engine.Forecast
import dev.baylem.treasury.engine.Occurrence

@Composable
internal fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
    }
}

@Composable
internal fun Eyebrow(text: String) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = Muted)
}

@Composable
internal fun EmptyState(title: String, detail: String, action: String? = null, onAction: () -> Unit = {}) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        Text(detail, color = Muted, style = MaterialTheme.typography.bodyMedium)
        if (action != null) FilledTonalButton(onClick = onAction) { Text(action) }
    }
}

@Composable
internal fun <T> ChoiceRow(values: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        values.forEach { value ->
            FilterChip(
                selected = value == selected,
                onClick = { onSelect(value) },
                label = { Text(label(value)) })
        }
    }
}

@Composable
internal fun OccurrenceRow(item: Occurrence, currency: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val color = if (item.direction == EntryDirection.INCOME) Income else Expense
        Surface(color = color.copy(alpha = .09f), shape = RoundedCornerShape(12.dp)) {
            Box(
                Modifier.size(42.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    if (item.direction == EntryDirection.INCOME) "↙" else "↗",
                    color = color,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text(
                item.category + if (item.isOverride) " · Changed" else "",
                style = MaterialTheme.typography.bodySmall,
                color = Muted
            )
        }
        Text(
            (if (item.direction == EntryDirection.INCOME) "+" else "−") + money(item.amountMinor, currency),
            color = color,
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
internal fun ForecastChart(forecast: Forecast, currency: String, modifier: Modifier = Modifier) {
    val values = listOf(forecast.openingBalanceMinor) + forecast.days.map { it.closingBalanceMinor }
    val low = values.minOrNull() ?: 0L
    val high = values.maxOrNull() ?: 0L
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(money(high, currency), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text("PROJECTED BALANCE", color = Muted, style = MaterialTheme.typography.labelSmall)
        }
        // Floating point positions pixels only, after the exact financial calculation.
        Canvas(
            Modifier.fillMaxWidth().height(140.dp).semantics {
                contentDescription = "Projected balance from ${money(values.first(), currency)} to ${
                    money(
                        values.last(),
                        currency
                    )
                }. Lowest ${money(low, currency)}."
            }) {
            val span = (high.toDouble() - low.toDouble()).coerceAtLeast(1.0)
            fun y(value: Long): Float =
                (size.height - 12f - ((value.toDouble() - low.toDouble()) / span * (size.height - 24f))).toFloat()
            repeat(3) { row ->
                val yy = 12f + row * (size.height - 24f) / 2; drawLine(
                Color(0xFFE7ECE5),
                Offset(0f, yy),
                Offset(size.width, yy),
                1f
            )
            }
            if (low < 0 && high > 0) drawLine(Expense.copy(alpha = .4f), Offset(0f, y(0)), Offset(size.width, y(0)), 1f)
            val line = Path()
            values.forEachIndexed { index, value ->
                val x = index.toFloat() / (values.size - 1).coerceAtLeast(1) * size.width; if (index == 0) line.moveTo(
                x,
                y(value)
            ) else line.lineTo(x, y(value))
            }
            val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
            drawPath(area, Pine.copy(alpha = .07f))
            drawPath(line, Pine, style = Stroke(width = 3f))
            drawCircle(Pine, 4f, Offset(size.width, y(values.last())))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(forecast.window.start.toString(), color = Muted, style = MaterialTheme.typography.labelSmall)
            Text(forecast.window.endInclusive.toString(), color = Muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}
