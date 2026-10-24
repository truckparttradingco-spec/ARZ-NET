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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.net.ssl.SSLException

private val Navy = Color(0xFF101D35)
private val Blue = Color(0xFF2563EB)
private val Page = Color(0xFFF4F6FA)
private val Green = Color(0xFF15966A)
private val Red = Color(0xFFD94747)
private val Amber = Color(0xFFB7791F)
private val Muted = Color(0xFF64748B)
private const val REFRESH_MS = 15_000L
private const val ENGINE_URL = "https://arz-net-forecast.onrender.com/quotes/current"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Blue,
                    background = Page,
                    surface = Color.White,
                    onPrimary = Color.White,
                    onBackground = Navy,
                    onSurface = Navy
                )
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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
    val price: Double,
    val unit: String,
    val source: String,
    val status: String = "unknown",
    val ageSeconds: Long? = null,
    val changePercent: Double? = null,
    val history: List<Double> = emptyList()
)

private data class QuoteDefinition(
    val id: String,
    val title: String,
    val flag: String,
    val unit: String = "تومان",
    val source: String = "موتور ARZ-NET"
)

private val quoteDefinitions = listOf(
    QuoteDefinition("usd_irr_toman", "دلار آزاد", "🇺🇸"),
    QuoteDefinition("eur_toman", "یورو", "🇪🇺"),
    QuoteDefinition("gbp_toman", "پوند انگلیس", "🇬🇧"),
    QuoteDefinition("aed_toman", "درهم امارات", "🇦🇪"),
    QuoteDefinition("cny_toman", "یوان چین", "🇨🇳"),
    QuoteDefinition("try_toman", "لیر ترکیه", "🇹🇷"),
    QuoteDefinition("cad_toman", "دلار کانادا", "🇨🇦"),
    QuoteDefinition("aud_toman", "دلار استرالیا", "🇦🇺"),
    QuoteDefinition("chf_toman", "فرانک سوئیس", "🇨🇭"),
    QuoteDefinition("sar_toman", "ریال عربستان", "🇸🇦"),
    QuoteDefinition("qar_toman", "ریال قطر", "🇶🇦"),
    QuoteDefinition("kwd_toman", "دینار کویت", "🇰🇼"),
    QuoteDefinition("omr_toman", "ریال عمان", "🇴🇲"),
    QuoteDefinition("iqd_toman", "دینار عراق", "🇮🇶"),
    QuoteDefinition("afn_toman", "افغانی افغانستان", "🇦🇫"),
    QuoteDefinition("rub_toman", "روبل روسیه", "🇷🇺"),
    QuoteDefinition("inr_toman", "روپیه هند", "🇮🇳"),
    QuoteDefinition("gold18_toman_per_gram", "طلای ۱۸ عیار", "🪙", "تومان / گرم"),
    QuoteDefinition("gold24_toman", "طلای ۲۴ عیار", "🪙", "تومان / گرم"),
    QuoteDefinition("gold_gram_toman", "قیمت هر گرم طلا", "✨", "تومان / گرم"),
    QuoteDefinition("gold_mesghal_toman", "مظنه طلا", "🪙", "تومان / مثقال"),
    QuoteDefinition("coin_bahar_toman", "سکه امامی / بهار آزادی", "🪙"),
    QuoteDefinition("coin_half_toman", "نیم‌سکه", "🪙"),
    QuoteDefinition("coin_quarter_toman", "ربع‌سکه", "🪙"),
    QuoteDefinition("silver_toman", "نقره", "⚒", "تومان / گرم"),
    QuoteDefinition("usdt_toman", "تتر", "₮"),
    QuoteDefinition("xau_usd", "اونس جهانی طلا", "🌍", "دلار / اونس"),
    QuoteDefinition("bitcoin_usd", "بیت‌کوین", "₿", "دلار")
)

private fun JSONObject.positiveDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() && it > 0.0 }
}

private fun fmt(value: Double, digits: Int = 0): String {
    val nf = NumberFormat.getNumberInstance(Locale("fa", "IR"))
    nf.maximumFractionDigits = digits
    nf.minimumFractionDigits = 0
    return nf.format(value)
}

private fun getJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 20_000
        readTimeout = 25_000
        useCaches = false
        setRequestProperty("Accept", "application/json")
        setRequestProperty("Cache-Control", "no-cache")
        setRequestProperty("User-Agent", "ARZ-NET-Android/1.4")
    }
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = body.replace(Regex("\\s+"), " ").take(350)
            throw IOException("HTTP $code${if (detail.isNotBlank()) " — $detail" else ""}")
        }
        if (body.isBlank()) throw IOException("پاسخ سرور خالی است (HTTP $code)")
        return try {
            JSONObject(body)
        } catch (e: Exception) {
            throw IOException("پاسخ سرور JSON معتبر نیست: ${e.message ?: "خطای تجزیه"}")
        }
    } finally {
        connection.disconnect()
    }
}

private suspend fun fetchEngineQuotes(): List<Quote> = withContext(Dispatchers.IO) {
    val engine = getJson(ENGINE_URL)
    val statuses = engine.optJSONObject("quote_status") ?: JSONObject()
    val sources = engine.optJSONObject("sources") ?: JSONObject()
    val latestRates = engine.optJSONObject("latest_market_rates") ?: JSONObject()
    val marketRates = engine.optJSONObject("market_rates") ?: JSONObject()
    val changes = engine.optJSONObject("market_change_percent") ?: JSONObject()

    quoteDefinitions.mapNotNull { definition ->
        val sourceInfo = sources.optJSONObject(definition.id) ?: JSONObject()
        val price = latestRates.positiveDouble(definition.id)
            ?: marketRates.positiveDouble(definition.id)
            ?: engine.positiveDouble(definition.id)
            ?: sourceInfo.positiveDouble("price")
            ?: return@mapNotNull null
        val age = if (sourceInfo.has("age_seconds") && !sourceInfo.isNull("age_seconds")) {
            sourceInfo.optLong("age_seconds").coerceAtLeast(0L)
        } else null
        val provider = sourceInfo.optString("provider", definition.source)
            .takeIf { it.isNotBlank() && it != "null" } ?: definition.source
        val change = if (changes.has(definition.id) && !changes.isNull(definition.id)) {
            changes.optDouble(definition.id, Double.NaN).takeIf { it.isFinite() }
        } else null
        Quote(
            id = definition.id,
            title = definition.title,
            flag = definition.flag,
            price = price,
            unit = definition.unit,
            source = provider,
            status = statuses.optString(definition.id, sourceInfo.optString("status", "unknown")),
            ageSeconds = age,
            changePercent = change
        )
    }
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf(emptyList<Quote>()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("در حال اتصال به موتور قیمت…") }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        while (isActive) {
            loading = true
            try {
                val fresh = fetchEngineQuotes()
                if (fresh.isEmpty()) {
                    message = "سرور پاسخ داد، اما قیمت معتبری در پاسخ پیدا نشد."
                } else {
                    val oldById = quotes.associateBy { it.id }
                    quotes = fresh.map { item ->
                        val oldHistory = oldById[item.id]?.history.orEmpty()
                        item.copy(history = (oldHistory + item.price).takeLast(96))
                    }
                    message = ""
                    lastUpdated = SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date())
                }
            } catch (e: Exception) {
                message = when (e) {
                    is UnknownHostException -> "آدرس سرور پیدا نشد. اینترنت و VPN را بررسی کن.\n${e.message.orEmpty()}"
                    is SocketTimeoutException -> "اتصال به سرور بیش از حد طول کشید. VPN یا وضعیت Render را بررسی کن.\n${e.message.orEmpty()}"
                    is SSLException -> "خطای امن‌سازی اتصال (SSL). VPN و تاریخ‌وساعت گوشی را بررسی کن.\n${e.message.orEmpty()}"
                    else -> "دریافت قیمت‌ها ناموفق بود: ${e.message ?: e.javaClass.simpleName}"
                }
            } finally {
                loading = false
            }
            delay(REFRESH_MS)
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().background(Page)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().background(Navy).padding(horizontal = 18.dp, vertical = 18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("ARZ-NET", color = Color.White, fontSize = 25.sp, fontWeight = FontWeight.ExtraBold)
                    Text("رصد بازار ارز، طلا و رمزارز", color = Color(0xFFD7E2F3), fontSize = 13.sp)
                }
                IconButton(onClick = { refreshTrigger++ }, enabled = !loading) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(23.dp), color = Color.White, strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "به‌روزرسانی", tint = Color.White)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (quotes.isNotEmpty() && message.isBlank()) Green else Amber))
                Spacer(Modifier.width(7.dp))
                Text(
                    text = if (quotes.isNotEmpty() && message.isBlank()) "متصل · آخرین به‌روزرسانی: $lastUpdated" else "وضعیت اتصال موتور قیمت",
                    color = Color(0xFFE2E8F0), fontSize = 12.sp
                )
            }
        }

        if (message.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF7ED)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(message, modifier = Modifier.fillMaxWidth().padding(13.dp), color = Color(0xFF9A3412), fontSize = 13.sp)
            }
        }

        if (quotes.isEmpty() && loading) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = Blue)
                    Spacer(Modifier.height(12.dp))
                    Text("در حال دریافت اطلاعات بازار…", color = Muted)
                }
            }
        } else if (quotes.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(24.dp)) {
                    Icon(Icons.Default.ShowChart, contentDescription = null, tint = Muted, modifier = Modifier.size(42.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("هنوز قیمتی دریافت نشده است", color = Navy, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Text("اگر لینک API در مرورگر باز می‌شود ولی اینجا خطا می‌بینی، دسترسی اینترنت برنامه و VPN را بررسی کن.", color = Muted, textAlign = TextAlign.Center, fontSize = 12.sp)
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { refreshTrigger++ }) { Text("تلاش دوباره") }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                items(quotes, key = { it.id }) { quote -> QuoteCard(quote) }
                item {
                    Text(
                        "به‌روزرسانی خودکار هر ۱۵ ثانیه · داده‌ها از موتور ARZ-NET دریافت می‌شوند",
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote) {
    val calculatedChange = quote.changePercent ?: run {
        val history = quote.history
        if (history.size >= 2 && history[history.size - 2] != 0.0) {
            ((history.last() - history[history.size - 2]) / history[history.size - 2]) * 100.0
        } else null
    }
    val changeColor = when {
        calculatedChange == null || calculatedChange == 0.0 -> Muted
        calculatedChange > 0 -> Green
        else -> Red
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(15.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(quote.flag, fontSize = 24.sp)
                Spacer(Modifier.width(9.dp))
                Column(Modifier.weight(1f)) {
                    Text(quote.title, color = Navy, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    Text(quote.source, color = Muted, fontSize = 10.sp)
                }
                if (calculatedChange != null) {
                    Text(
                        text = (if (calculatedChange > 0) "+" else "") + fmt(calculatedChange, 2) + "%",
                        color = changeColor, fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(fmt(quote.price, if (quote.price < 1000) 2 else 0), color = Navy, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold)
                    Text(quote.unit, color = Muted, fontSize = 11.sp)
                }
                Sparkline(quote.history, Modifier.width(92.dp).height(36.dp), changeColor)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val statusColor = when (quote.status.lowercase()) {
                    "fresh", "received", "cached" -> Green
                    "stale", "timestamp_unverified", "timestamp_in_future" -> Amber
                    else -> Muted
                }
                Box(Modifier.size(6.dp).clip(CircleShape).background(statusColor))
                Spacer(Modifier.width(5.dp))
                Text(statusLabel(quote.status), color = statusColor, fontSize = 10.sp)
                Spacer(Modifier.weight(1f))
                quote.ageSeconds?.let { Text("سن داده: ${formatAge(it)}", color = Muted, fontSize = 10.sp) }
            }
        }
    }
}

private fun statusLabel(status: String): String = when (status.lowercase()) {
    "fresh" -> "به‌روز"
    "received" -> "دریافت‌شده"
    "cached" -> "ذخیره‌شده در حافظه"
    "stale" -> "قدیمی"
    "timestamp_unverified" -> "زمان تأییدنشده"
    "timestamp_in_future" -> "زمان نامعتبر"
    else -> "وضعیت: $status"
}

private fun formatAge(seconds: Long): String = when {
    seconds < 60 -> "$seconds ثانیه"
    seconds < 3600 -> "${seconds / 60} دقیقه"
    else -> "${seconds / 3600} ساعت"
}

@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, color: Color = Blue) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas
        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val range = (max - min).takeIf { it > 0.0 } ?: 1.0
        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height - (((value - min) / range).toFloat() * (size.height - 4.dp.toPx())) - 2.dp.toPx()
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()))
    }
}
