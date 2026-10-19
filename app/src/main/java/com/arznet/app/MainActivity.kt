package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF101D35)
private val Blue = Color(0xFF2563EB)
private val Page = Color(0xFFF4F6FA)
private val Green = Color(0xFF15966A)
private val Red = Color(0xFFD94747)
private const val OUNCE_GRAMS = 31.1034768
private const val REFRESH_MS = 15_000L

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Blue,
                        background = Page,
                        surface = Color.White
                    )
                ) {
                    ArzNetScreen()
                }
            }
        }
    }
}

private data class Quote(
    val id: String,
    val title: String,
    val flag: String,
    val price: Double?,
    val unit: String,
    val source: String,
    val note: String = "",
    val history: List<Double> = emptyList()
)

private data class FeedResult(
    val usdToman: Double? = null,
    val gold18Toman: Double? = null,
    val mesghalToman: Double? = null,
    val tetherToman: Double? = null,
    val aedToman: Double? = null,
    val iqdToman: Double? = null
)

private fun fmt(value: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)

private fun getJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
        readTimeout = 10_000
        setRequestProperty("Accept", "application/json")
        setRequestProperty("User-Agent", "ARZ-NET-Android/1.1")
    }
    try {
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val text = connection.inputStream.bufferedReader().use { it.readText() }
        return JSONObject(text)
    } finally {
        connection.disconnect()
    }
}

private fun validPrice(item: JSONObject): Double? {
    val value = item.optDouble("price", Double.NaN)
    return value.takeIf { it.isFinite() && it > 0.0 }
}

/** Reads symbols and category labels because feed versions may identify an asset either way. */
private suspend fun fetchIranFeed(): FeedResult = withContext(Dispatchers.IO) {
    val root = getJson("https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/popular.json")
    val data = root.optJSONArray("data") ?: JSONArray()
    var usd: Double? = null
    var gold18: Double? = null
    var mesghal: Double? = null
    var tether: Double? = null
    var aed: Double? = null
    var iqd: Double? = null

    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue
        val price = validPrice(item) ?: continue
        val symbol = item.optString("symbol").uppercase(Locale.ROOT)
        val name = item.optString("name").lowercase(Locale.ROOT)
        val title = item.optString("title").lowercase(Locale.ROOT)
        val categories = item.optJSONArray("categories")
        val categoryText = buildString {
            if (categories != null) {
                for (j in 0 until categories.length()) append(' ').append(categories.optString(j).lowercase(Locale.ROOT))
            }
            val singleCategory = item.optString("category")
            if (singleCategory.isNotBlank()) append(' ').append(singleCategory.lowercase(Locale.ROOT))
        }
        val labels = "$name $title $categoryText"

        when {
            symbol in setOf("USD_IRR_FREE", "USD_TOMAN", "USD_IRR") ||
                (labels.contains("dollar") && !labels.contains("canadian")) -> usd = price
            symbol in setOf("GOLD_18K_IRR", "GOLD_18K_TOMAN") ||
                (labels.contains("gold") && (labels.contains("18") || labels.contains("18k"))) -> gold18 = price
            symbol in setOf("GOLD_MESGHAL_IRR", "MESGHAL_GOLD", "GOLD_MESGHAL") ||
                labels.contains("mesghal") -> mesghal = price
            symbol in setOf("USDT_IRR", "USDT_TOMAN", "TETHER_IRR", "TETHER_TOMAN") ||
                symbol.contains("USDT") || labels.contains("tether") -> tether = price
            symbol in setOf("AED_IRR_FREE", "AED_IRR", "AED_TOMAN") ||
                symbol.startsWith("AED_") || labels.contains("dirham") || labels.contains("درهم") -> aed = price
            symbol in setOf("IQD_IRR_FREE", "IQD_IRR", "IQD_TOMAN") ||
                symbol.startsWith("IQD_") || labels.contains("iraqi dinar") || labels.contains("دینار عراق") -> iqd = price
        }
    }
    FeedResult(usd, gold18, mesghal, tether, aed, iqd)
}

private suspend fun fetchGoldOunce(): Pair<Double?, String> = withContext(Dispatchers.IO) {
    try {
        val json = getJson("https://api.gold-api.com/price/XAU")
        val price = json.optDouble("price", Double.NaN).takeIf { it.isFinite() && it > 0.0 }
        if (price != null) return@withContext price to "Gold-API"
    } catch (_: Exception) {
        // Keep the dashboard running if this provider is unavailable.
    }
    null to "قیمت در دسترس نیست"
}

private suspend fun fetchBitcoin(): Double? = withContext(Dispatchers.IO) {
    try {
        getJson("https://api.gold-api.com/price/BTC")
            .optDouble("price", Double.NaN)
            .takeIf { it.isFinite() && it > 0.0 }
    } catch (_: Exception) {
        null
    }
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf(emptyList<Quote>()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("در حال دریافت قیمت‌ها…") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        while (true) {
            loading = true
            try {
                val feed = try {
                    fetchIranFeed()
                } catch (_: Exception) {
                    FeedResult()
                }
                val (ounce, ounceSource) = fetchGoldOunce()
                val btc = fetchBitcoin()
                val usd = feed.usdToman
                val next = mutableListOf<Quote>()

                if (usd != null) next += Quote("usd", "دلار آزاد", "🇺🇸", usd, "تومان", "فید بازار ایران")
                if (feed.tetherToman != null) {
                    next += Quote("usdt", "تتر", "₮", feed.tetherToman, "تومان", "فید بازار ایران")
                } else if (usd != null) {
                    next += Quote("usdt-est", "تتر (برآوردی)", "₮", usd, "تومان", "برآورد بر پایه دلار آزاد",
                        "قیمت مستقل تتر از منبع دریافت نشد.")
                }
                if (feed.aedToman != null) next += Quote("aed", "درهم امارات", "🇦🇪", feed.aedToman, "تومان", "فید بازار ایران")
                if (feed.iqdToman != null) next += Quote("iqd", "دینار عراق", "🇮🇶", feed.iqdToman, "تومان", "فید بازار ایران؛ نماد IQD_IRR_FREE")

                if (ounce != null) next += Quote("ounce", "اونس جهانی طلا", "🌐", ounce, "دلار / اونس", ounceSource)
                if (feed.gold18Toman != null) next += Quote("gold18", "طلای ۱۸ عیار", "🟡", feed.gold18Toman, "تومان / گرم", "فید بازار ایران")
                if (feed.mesghalToman != null) {
                    next += Quote("mesghal", "مثقال طلا", "🟡", feed.mesghalToman, "تومان / مثقال", "فید بازار ایران")
                } else if (ounce != null && usd != null) {
                    val estimated = ounce / OUNCE_GRAMS * usd * 17.0665 * (18.0 / 24.0)
                    next += Quote("mesghal-est", "مثقال طلا (برآوردی)", "🟡", estimated, "تومان / مثقال",
                        "محاسبه از اونس و دلار آزاد", "این عدد برآورد محاسباتی است، نه نرخ مستقل بازار.")
                }
                if (ounce != null && usd != null) {
                    next += Quote("gold24", "طلای ۲۴ عیار (محاسباتی)", "🟡",
                        ounce / OUNCE_GRAMS * usd, "تومان / گرم", "محاسبه از اونس و دلار آزاد")
                }
                if (btc != null) next += Quote("btc", "بیت‌کوین", "₿", btc, "دلار", "Gold-API")
                if (btc != null && usd != null) {
                    next += Quote("btc-irr", "بیت‌کوین (برآورد ریالی)", "₿", btc * usd, "تومان", "محاسبه از BTC/USD و دلار آزاد")
                }

                quotes = next.map { fresh ->
                    val old = quotes.firstOrNull { it.id == fresh.id }
                    fresh.copy(history = (old?.history.orEmpty() + listOfNotNull(fresh.price)).takeLast(96))
                }
                lastUpdated = SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date())
                message = if (next.isEmpty()) {
                    "قیمت زنده دریافت نشد؛ اتصال اینترنت یا منبع داده را بررسی کنید."
                } else ""
            } catch (_: Exception) {
                message = "دریافت قیمت‌ها با مشکل مواجه شد؛ بعداً دوباره تلاش می‌کنیم."
            } finally {
                loading = false
            }
            delay(REFRESH_MS)
        }
    }

    Column(Modifier.fillMaxSize().background(Page)) {
        Column(Modifier.fillMaxWidth().background(Navy).padding(horizontal = 20.dp, vertical = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ARZ-NET", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text("قیمت لحظه‌ای بازارهای مالی", color = Color(0xFFCAD5E8), fontSize = 13.sp)
                }
                Box(
                    Modifier.size(48.dp).clip(RoundedCornerShape(15.dp)).background(Blue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.ShowChart, contentDescription = "ARZ-NET", tint = Color.White, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("نمای کلی بازار", color = Color.White, fontWeight = FontWeight.SemiBold)
                    Text(lastUpdated?.let { "آخرین به‌روزرسانی: $it" } ?: "در انتظار دریافت داده",
                        color = Color(0xFFCAD5E8), fontSize = 12.sp)
                }
                FilledTonalButton(
                    onClick = { refreshTrigger++ },
                    enabled = !loading,
                    colors = ButtonDefaults.filledTonalButtonColors(containerColor = Color.White, contentColor = Navy)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(if (loading) "در حال دریافت" else "بروزرسانی")
                }
            }
        }

        if (message.isNotBlank()) {
            Text(message, color = if (quotes.isEmpty()) Red else Navy,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                textAlign = TextAlign.Center, fontSize = 13.sp)
        }
        Text("نرخ‌ها هر ۱۵ ثانیه بررسی می‌شوند؛ سرعت تغییر به منبع داده وابسته است.",
            color = Color(0xFF64748B), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            items(quotes, key = { it.id }) { quote -> QuoteCard(quote) }
            item {
                Text("نمودارها از زمان اجرای برنامه و از دریافت‌های موفق جمع‌آوری می‌شوند؛ تاریخچه کامل ۲۴ ساعته نیست.",
                    color = Color(0xFF64748B), fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp))
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote) {
    val previous = quote.history.getOrNull(quote.history.lastIndex - 1)
    val current = quote.price
    val change = if (current != null && previous != null && previous != 0.0) {
        (current - previous) / previous * 100.0
    } else null
    val changeColor = if (change != null && change < 0) Red else Green

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).clip(CircleShape).background(Color(0xFFF0F5FF)), contentAlignment = Alignment.Center) {
                    Text(quote.flag, fontSize = 22.sp)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(quote.title, fontWeight = FontWeight.SemiBold, color = Navy, fontSize = 15.sp)
                    Text(quote.source, color = Color(0xFF778399), fontSize = 11.sp)
                }
                if (change != null) {
                    Text("${if (change >= 0) "+" else ""}${fmt(change, 2)}٪",
                        color = changeColor, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(current?.let { fmt(it, 2) } ?: "ناموجود",
                        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                    Text(quote.unit, color = Color(0xFF64748B), fontSize = 12.sp)
                }
                if (quote.history.size >= 2) {
                    Sparkline(quote.history, Modifier.width(104.dp).height(38.dp), changeColor)
                }
            }
            if (quote.note.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(quote.note, color = Color(0xFF7A8496), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = Blue) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val minValue = values.minOrNull() ?: return@Canvas
        val maxValue = values.maxOrNull() ?: return@Canvas
        val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height - ((value - minValue) / range).toFloat() * size.height
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f))
    }
}
