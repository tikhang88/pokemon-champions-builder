package hk.tikhang.champions.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import hk.tikhang.champions.core.Verification
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val Red = Color(0xFFD7263D)

@Composable
fun ChampionsTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = Color(0xFFFF8A80), secondary = Color(0xFF90CAF9), tertiary = Color(0xFFFFD54F))
    } else {
        lightColorScheme(primary = Red, secondary = Color(0xFF1E5AA8), tertiary = Color(0xFFB7791F))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

val TypeColors: Map<String, Color> = mapOf(
    "Normal" to Color(0xFF9FA19F), "Fire" to Color(0xFFE62829), "Water" to Color(0xFF2980EF),
    "Electric" to Color(0xFFFAC000), "Grass" to Color(0xFF3FA129), "Ice" to Color(0xFF3DCEF3),
    "Fighting" to Color(0xFFFF8000), "Poison" to Color(0xFF9141CB), "Ground" to Color(0xFF915121),
    "Flying" to Color(0xFF81B9EF), "Psychic" to Color(0xFFEF4179), "Bug" to Color(0xFF91A119),
    "Rock" to Color(0xFFAFA981), "Ghost" to Color(0xFF704170), "Dragon" to Color(0xFF5060E1),
    "Dark" to Color(0xFF624D4E), "Steel" to Color(0xFF60A1B8), "Fairy" to Color(0xFFEF70EF),
)

val TypeZh: Map<String, String> = mapOf(
    "Normal" to "一般", "Fire" to "火", "Water" to "水", "Electric" to "電", "Grass" to "草", "Ice" to "冰",
    "Fighting" to "格鬥", "Poison" to "毒", "Ground" to "地面", "Flying" to "飛行", "Psychic" to "超能力",
    "Bug" to "蟲", "Rock" to "岩石", "Ghost" to "幽靈", "Dragon" to "龍", "Dark" to "惡", "Steel" to "鋼", "Fairy" to "妖精",
)

val CategoryZh: Map<String, String> = mapOf("Physical" to "物理", "Special" to "特殊", "Status" to "變化")

@Composable
fun TypeChip(type: String, modifier: Modifier = Modifier) {
    val dark = type in setOf("Electric", "Ice", "Normal", "Rock", "Flying", "Bug", "Fairy")
    Box(
        modifier
            .clip(RoundedCornerShape(6.dp))
            .background(TypeColors[type] ?: Color.Gray)
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) {
        Text(TypeZh[type] ?: type, color = if (dark) Color(0xFF1A1A1A) else Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun TypeRow(types: List<String>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) { types.forEach { TypeChip(it) } }
}

@Composable
fun Badge(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium) }
}

@Composable
fun VerificationBadge(v: String) {
    val color = when (v) {
        Verification.OFFICIAL, Verification.VERIFIED -> Color(0xFF2E7D32)
        Verification.COMMUNITY -> Color(0xFF1565C0)
        else -> Color(0xFFB26A00)
    }
    Badge(Verification.label(v), color)
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
fun LabelValue(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(label, Modifier.weight(0.38f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.62f), style = MaterialTheme.typography.bodyMedium)
    }
}

private val DateFmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** ISO time → local time text, e.g. "2026-12-02 10:00". */
fun localTime(iso: String?): String =
    iso?.let { runCatching { DateFmt.format(Instant.parse(it).atZone(ZoneId.systemDefault())) }.getOrNull() } ?: "未定"

fun localTime(millis: Long): String = DateFmt.format(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()))

fun dex(num: Int): String = "#" + num.toString().padStart(4, '0')
