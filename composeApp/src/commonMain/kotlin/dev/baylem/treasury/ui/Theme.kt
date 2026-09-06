package dev.baylem.treasury.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.baylem.treasury.domain.Money
import kotlinx.datetime.LocalDate

internal val Pine = Color(0xFF174A3C)
internal val Muted = Color(0xFF67756D)
internal val Income = Color(0xFF20775B)
internal val Expense = Color(0xFFB24A3D)
internal val Paper = Color(0xFFF5F7F3)

@Composable
fun TreasuryTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Pine, onPrimary = Color.White, primaryContainer = Color(0xFFE0EDE5),
            onPrimaryContainer = Pine, secondary = Color(0xFF886A38),
            secondaryContainer = Color(0xFFF3E9D7), background = Paper,
            surface = Color.White, onSurface = Color(0xFF1E3028),
            onSurfaceVariant = Muted, surfaceVariant = Color(0xFFEDF1EC),
            outline = Color(0xFF89968D), outlineVariant = Color(0xFFDCE3DA),
            error = Expense, errorContainer = Color(0xFFFCE8E3),
        ),
        typography = Typography(
            displaySmall = TextStyle(fontFamily = FontFamily.Serif, fontSize = 36.sp, lineHeight = 42.sp),
            headlineLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 32.sp, lineHeight = 38.sp),
            headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp),
            titleLarge = TextStyle(fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
            titleMedium = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold),
            bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
            bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 21.sp),
            labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
            labelSmall = TextStyle(fontSize = 10.sp, letterSpacing = 0.7.sp, fontWeight = FontWeight.SemiBold),
        ),
        shapes = Shapes(
            small = RoundedCornerShape(10.dp),
            medium = RoundedCornerShape(16.dp),
            large = RoundedCornerShape(22.dp)
        ),
        content = content,
    )
}

internal fun money(amount: Long, currency: String): String {
    val parts = Money.formatMinor(amount).removePrefix("-").split('.')
    val grouped = parts[0].reversed().chunked(3).joinToString(",").reversed()
    val symbol = when (currency) {
        "USD" -> "$"; "GBP" -> "£"; "EUR" -> "€"; "CAD" -> "CA$"; "AUD" -> "A$"; else -> "$currency "
    }
    return (if (amount < 0) "−" else "") + symbol + grouped + "." + parts[1]
}

internal val monthNames = listOf(
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December"
)

internal fun monthLabel(date: LocalDate) = "${monthNames[date.month.ordinal]} ${date.year}"
internal fun dayLabel(date: LocalDate) = "${
    date.dayOfWeek.name.lowercase().replaceFirstChar { it.uppercase() }
}, ${monthNames[date.month.ordinal].take(3)} ${date.day}"
