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
private val Amber = Color(0xFFB7791F)
private val Muted = Color(0xFF64748B)

private const val REFRESH_MS = 15_000L
private const val ENGINE_URL =
    "http://192.168.1.106:8000/quotes/current"

private data class Quote(
    val id: String,
    val title: String,
    val flag: String,
    val price: Double?,
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
    val unit: String,
    val source: String
)

private val quoteDefinitions = listOf(
    QuoteDefinition("usd_irr_toman", "دلار آزاد", "🇺🇸", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("eur_toman", "یورو", "🇪🇺", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("gbp_toman", "پوند انگلیس", "🇬🇧", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("aed_toman", "درهم امارات", "🇦🇪", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("cny_toman", "یوان چین", "🇨🇳", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("try_toman", "لیر ترکیه", "🇹🇷", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("cad_toman", "دلار کانادا", "🇨🇦", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("aud_toman", "دلار استرالیا", "🇦🇺", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("chf_toman", "فرانک سوئیس", "🇨🇭", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("sar_toman", "ریال عربستان", "🇸🇦", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("qar_toman", "ریال قطر", "🇶🇦", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("kwd_toman", "دینار کویت", "🇰🇼", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("omr_toman", "ریال عمان", "🇴🇲", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("iqd_toman", "دینار عراق", "🇮🇶", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("afn_toman", "افغانی افغانستان", "🇦🇫", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("rub_toman", "روبل روسیه", "🇷🇺", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("inr_toman", "روپیه هند", "🇮🇳", "تومان", "بازار آزاد ایران"),
    QuoteDefinition("gold18_toman_per_gram", "طلای ۱۸ عیار", "🟡", "تومان / گرم", "بازار طلای ایران"),
    QuoteDefinition("gold24_toman", "طلای ۲۴ عیار", "🟨", "تومان", "بازار طلای ایران"),
    QuoteDefinition("gold_gram_toman", "قیمت هر گرم طلا", "✨", "تومان", "بازار طلای ایران"),
    QuoteDefinition("gold_mesghal_toman", "مظنه طلا", "🟡", "تومان / مثقال", "بازار طلای ایران"),
    QuoteDefinition("coin_bahar_toman", "سکه امامی / بهار آزادی", "🪙", "تومان", "بازار سکه ایران"),
    QuoteDefinition("coin_half_toman", "نیم‌سکه", "🪙", "تومان", "بازار سکه ایران"),
    QuoteDefinition("coin_quarter_toman", "ربع‌سکه", "🪙", "تومان", "بازار سکه ایران"),
    QuoteDefinition("silver_toman", "نقره", "⚪", "تومان", "بازار فلزات"),
    QuoteDefinition("usdt_toman", "تتر", "₮", "تومان", "بازار تتر ایران"),
    QuoteDefinition("xau_usd", "اونس جهانی طلا", "🌐", "دلار / اونس", "بازار جهانی"),
    QuoteDefinition("bitcoin_usd", "بیت‌کوین", "₿", "دلار", "CoinGecko")
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
        setRequestProperty("User-Agent", "ARZ-NET-Android/1.2")
    }

    try {
        if (connection.responseCode !in 200..299) {
            error("HTTP ${connection.responseCode}")
        }

        val text = connection.inputStream
            .bufferedReader()
            .use { it.readText() }

        return JSONObject(text)
    } finally {
        connection.disconnect()
    }
}

private fun JSONObject.positiveDouble(key: String): Double? {
    if (!has(key) || isNull(key)) return null

    val value = optDouble(key, Double.NaN)
    return value.takeIf { it.isFinite() && it > 0.0 }
}

private suspend fun fetchEngineQuotes(): List<Quote> =
    withContext(Dispatchers.IO) {
        val engine = getJson(ENGINE_URL)
        val statuses = engine.optJSONObject("quote_status") ?: JSONObject()
        val sources = engine.optJSONObject("sources") ?: JSONObject()

        // latest_market_rates شامل آخرین قیمت دریافت‌شده است.
        // market_rates فقط نرخ‌هایی را شامل می‌شود که موتور آن‌ها را تازه تشخیص داده است.
        val latest = engine.optJSONObject("latest_market_rates") ?: JSONObject()
        val freshRates = engine.optJSONObject("market_rates") ?: JSONObject()
        val changes = engine.optJSONObject("market_change_percent") ?: JSONObject()

        val result = mutableListOf<Quote>()

        for (definition in quoteDefinitions) {
            val sourceInfo = sources.optJSONObject(definition.id) ?: JSONObject()
            val status = statuses.optString(definition.id, "unknown")
            val value = latest.positiveDouble(definition.id)
                ?: freshRates.positiveDouble(definition.id)
                ?: engine.positiveDouble(definition.id)

            if (value == null) continue

            val age = if (sourceInfo.has("age_seconds") &&
                !sourceInfo.isNull("age_seconds")
            ) {
                sourceInfo.optLong("age_seconds").coerceAtLeast(0L)
            } else {
                null
            }

            val change = changes.optDouble(
                definition.id,
                Double.NaN
            ).takeIf { it.isFinite() }

            val provider = sourceInfo.optString(
                "provider",
                definition.source
            )

            result += Quote(
                id = definition.id,
                title = definition.title,
                flag = definition.flag,
                price = value,
                unit = definition.unit,
                source = provider,
                status = status,
                ageSeconds = age,
                changePercent = change
            )
        }

        result
    }

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf(emptyList<Quote>()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf<String?>(null) }
    var message by remember {
        mutableStateOf("در حال اتصال به موتور ARZ-NET…")
    }
    var refreshTrigger by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshTrigger) {
        while (true) {
            loading = true

            try {
                val fetched = fetchEngineQuotes()

                quotes = fetched.map { fresh ->
                    val old = quotes.firstOrNull { it.id == fresh.id }
                    fresh.copy(
                        history = (
                            old?.history.orEmpty() +
                                listOfNotNull(fresh.price)
                            ).takeLast(96)
                    )
                }

                lastUpdated = SimpleDateFormat(
                    "HH:mm:ss",
                    Locale("fa", "IR")
                ).format(Date())

                message = if (fetched.isEmpty()) {
                    "قیمتی از API دریافت نشد؛ وضعیت موتور را بررسی کنید."
                } else {
                    ""
                }
            } catch (e: Exception) {
                message =
                    "اتصال به موتور برقرار نشد. سرور، آدرس IP و وای‌فای را بررسی کنید."
            } finally {
                loading = false
            }

            delay(REFRESH_MS)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(Page)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Navy)
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ARZ-NET",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "قیمت ارز، طلا و بازارهای جهانی",
                        color = Color(0xFFCAD5E8),
                        fontSize = 13.sp
                    )
                }

                Box(
                    Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(Blue),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.ShowChart,
                        contentDescription = "ARZ-NET",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "نمای کلی بازار",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        lastUpdated?.let { "آخرین دریافت: $it" }
                            ?: "در انتظار دریافت داده",
                        color = Color(0xFFCAD5E8),
                        fontSize = 12.sp
                    )
                }

                FilledTonalButton(
                    onClick = { refreshTrigger++ },
                    enabled = !loading,
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Color.White,
                        contentColor = Navy
                    )
                ) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(if (loading) "در حال دریافت" else "بروزرسانی")
                }
            }
        }

        if (message.isNotBlank()) {
            Text(
                message,
                color = if (quotes.isEmpty()) Red else Navy,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                textAlign = TextAlign.Center,
                fontSize = 13.sp
            )
        }

        Text(
            "به‌روزرسانی خودکار هر ۱۵ ثانیه؛ تازگی قیمت وابسته به منبع است.",
            color = Muted,
            fontSize = 11.sp,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 14.dp,
                end = 14.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            items(quotes, key = { it.id }) { quote ->
                QuoteCard(quote)
            }

            item {
                Text(
                    "نمودار کوچک هر کارت از دریافت‌های موفق همین اجرای برنامه ساخته می‌شود؛ تاریخچه کامل ۲۴ ساعته نیست.",
                    color = Muted,
                    fontSize = 11.sp,
                    modifier = Modifier.padding(top = 6.dp, bottom = 12.dp)
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote) {
    val previous = quote.history.getOrNull(quote.history.lastIndex - 1)
    val current = quote.price

    val historyChange = if (
        current != null && previous != null && previous != 0.0
    ) {
        (current - previous) / previous * 100.0
    } else {
        null
    }

    // اگر API تغییر درصدی منبع را فرستاده باشد، آن را نشان می‌دهیم.
    val shownChange = quote.changePercent ?: historyChange
    val changeColor = when {
        shownChange == null -> Muted
        shownChange < 0 -> Red
        else -> Green
    }

    val statusText = when (quote.status) {
        "received", "fresh" -> "دریافت موفق"
        "stale" -> "داده قدیمی"
        "timestamp_unverified" -> "زمان تأیید نشده"
        "timestamp_in_future" -> "زمان نامعتبر"
        "unknown" -> "وضعیت نامشخص"
        else -> quote.status
    }

    val statusColor = when (quote.status) {
        "received", "fresh" -> Green
        "stale", "timestamp_unverified" -> Amber
        else -> Red
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF0F5FF)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(quote.flag, fontSize = 22.sp)
                }

                Spacer(Modifier.width(12.dp))

                Column(Modifier.weight(1f)) {
                    Text(
                        quote.title,
                        fontWeight = FontWeight.SemiBold,
                        color = Navy,
                        fontSize = 15.sp
                    )
                    Text(
                        quote.source,
                        color = Muted,
                        fontSize = 11.sp
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        statusText,
                        color = statusColor,
                        fontWeight = FontWeight.Medium,
                        fontSize = 11.sp
                    )
                    if (shownChange != null) {
                        Text(
                            "${if (shownChange >= 0) "+" else ""}${fmt(shownChange, 2)}٪",
                            color = changeColor,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.let { fmt(it, 2) } ?: "ناموجود",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy
                    )
                    Text(
                        quote.unit,
                        color = Muted,
                        fontSize = 12.sp
                    )
                }

                if (quote.history.size >= 2) {
                    Sparkline(
                        quote.history,
                        Modifier
                            .width(104.dp)
                            .height(38.dp),
                        changeColor
                    )
                }
            }

            quote.ageSeconds?.let { age ->
                Spacer(Modifier.height(6.dp))
                Text(
                    "سن داده: ${formatAge(age)}",
                    color = if (age > 1800) Amber else Muted,
                    fontSize = 11.sp
                )
            }
        }
    }
}

private fun formatAge(seconds: Long): String {
    return when {
        seconds < 60 -> "$seconds ثانیه"
        seconds < 3600 -> "${seconds / 60} دقیقه"
        else -> "${seconds / 3600} ساعت و ${(seconds % 3600) / 60} دقیقه"
    }
}

@Composable
private fun Sparkline(
    values: List<Double>,
    modifier: Modifier = Modifier,
    color: Color = Blue
) {
    Canvas(modifier = modifier) {
        if (values.size < 2) return@Canvas

        val minValue = values.minOrNull() ?: return@Canvas
        val maxValue = values.maxOrNull() ?: return@Canvas
        val range = (maxValue - minValue).takeIf { it > 0.0 } ?: 1.0
        val path = Path()

        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height -
                ((value - minValue) / range).toFloat() * size.height

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
    }
}
