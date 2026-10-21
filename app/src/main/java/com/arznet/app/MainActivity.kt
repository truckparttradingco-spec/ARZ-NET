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

private const val REFRESH_MS = 15_000L
private const val ENGINE_URL =
    "http://192.168.1.106:8000/quotes/current"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            CompositionLocalProvider(
                LocalLayoutDirection provides LayoutDirection.Rtl
            ) {
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

private fun fmt(value: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)

private fun getJson(url: String): JSONObject {
    val connection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = 10_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "ARZ-NET-Android/1.1")
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

private suspend fun fetchEngineQuotes(): List<Quote> =
    withContext(Dispatchers.IO) {
        val engine = getJson(ENGINE_URL)
        val status = engine.optJSONObject("quote_status")
            ?: JSONObject()

        fun isFresh(statusKey: String): Boolean {
            return status.optString(statusKey) == "received"
        }

        fun price(key: String): Double? {
            val value = engine.optDouble(key, Double.NaN)
            return value.takeIf {
                it.isFinite() && it > 0.0
            }
        }

        val result = mutableListOf<Quote>()

        // نرخ دلار: فقط وقتی وضعیت دلار در موتور تازه باشد.
        if (isFresh("usd_irr_toman")) {
            val usd = price("usd_irr_toman")
                ?: run {
                    val buy = price("usd_buy_toman")
                    val sell = price("usd_sell_toman")

                    if (buy != null && sell != null) {
                        (buy + sell) / 2.0
                    } else {
                        buy ?: sell
                    }
                }

            if (usd != null) {
                result += Quote(
                    id = "usd",
                    title = "دلار آزاد",
                    flag = "🇺🇸",
                    price = usd,
                    unit = "تومان",
                    source = "موتور ARZ-NET / HANSHA"
                )
            }
        }

        // طلای ۱۸ عیار
        if (isFresh("gold18_toman_per_gram")) {
            price("gold18_toman_per_gram")?.let {
                result += Quote(
                    id = "gold18",
                    title = "طلای ۱۸ عیار",
                    flag = "🟡",
                    price = it,
                    unit = "تومان / گرم",
                    source = "موتور ARZ-NET"
                )
            }
        }

        // اونس جهانی طلا
        if (isFresh("xau_usd")) {
            price("xau_usd")?.let {
                result += Quote(
                    id = "ounce",
                    title = "اونس جهانی طلا",
                    flag = "🌐",
                    price = it,
                    unit = "دلار / اونس",
                    source = "موتور ARZ-NET"
                )
            }
        }

        // بیت‌کوین
        if (isFresh("bitcoin_usd")) {
            price("bitcoin_usd")?.let {
                result += Quote(
                    id = "btc",
                    title = "بیت‌کوین",
                    flag = "₿",
                    price = it,
                    unit = "دلار",
                    source = "موتور ARZ-NET"
                )
            }
        }

        result
    }

@Composable
private fun ArzNetScreen() {
    var quotes by remember {
        mutableStateOf(emptyList<Quote>())
    }
    var loading by remember {
        mutableStateOf(false)
    }
    var lastUpdated by remember {
        mutableStateOf<String?>(null)
    }
    var message by remember {
        mutableStateOf("در حال اتصال به موتور ARZ-NET…")
    }
    var refreshTrigger by remember {
        mutableIntStateOf(0)
    }

    LaunchedEffect(refreshTrigger) {
        while (true) {
            loading = true

            try {
                val fetched = fetchEngineQuotes()

                quotes = fetched.map { fresh ->
                    val old = quotes.firstOrNull {
                        it.id == fresh.id
                    }

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
                    "داده تازه در دسترس نیست؛ وضعیت منابع موتور را بررسی کنید."
                } else {
                    ""
                }
            } catch (e: Exception) {
                message =
                    "اتصال به موتور برقرار نشد. روشن بودن سرور و اتصال وای‌فای را بررسی کنید."
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
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "ARZ-NET",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "قیمت لحظه‌ای بازارهای مالی",
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

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "نمای کلی بازار",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        lastUpdated?.let {
                            "آخرین دریافت: $it"
                        } ?: "در انتظار دریافت داده",
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
                    Text(
                        if (loading) "در حال دریافت"
                        else "بروزرسانی"
                    )
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
            "منبع داده: موتور ARZ-NET؛ به‌روزرسانی هر ۱۵ ثانیه",
            color = Color(0xFF64748B),
            fontSize = 11.sp,
            modifier = Modifier.padding(
                horizontal = 16.dp,
                vertical = 4.dp
            )
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
                    "نمودارها از دریافت‌های موفق در همین اجرا ساخته می‌شوند و تاریخچه کامل ۲۴ ساعته نیست.",
                    color = Color(0xFF64748B),
                    fontSize = 11.sp,
                    modifier = Modifier.padding(
                        top = 6.dp,
                        bottom = 12.dp
                    )
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote) {
    val previous = quote.history.getOrNull(
        quote.history.lastIndex - 1
    )
    val current = quote.price

    val change = if (
        current != null &&
        previous != null &&
        previous != 0.0
    ) {
        (current - previous) / previous * 100.0
    } else {
        null
    }

    val changeColor =
        if (change != null && change < 0) Red else Green

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = 1.dp
        )
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
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
                        color = Color(0xFF778399),
                        fontSize = 11.sp
                    )
                }

                if (change != null) {
                    Text(
                        "${if (change >= 0) "+" else ""}${fmt(change, 2)}٪",
                        color = changeColor,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            Row(
                verticalAlignment = Alignment.Bottom
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        current?.let { fmt(it, 2) } ?: "ناموجود",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy
                    )
                    Text(
                        quote.unit,
                        color = Color(0xFF64748B),
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

            if (quote.note.isNotBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    quote.note,
                    color = Color(0xFF7A8496),
                    fontSize = 11.sp
                )
            }
        }
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

        val minValue = values.minOrNull()
            ?: return@Canvas
        val maxValue = values.maxOrNull()
            ?: return@Canvas
        val range = (maxValue - minValue)
            .takeIf { it > 0.0 } ?: 1.0

        val path = Path()

        values.forEachIndexed { index, value ->
            val x = size.width * index /
                (values.size - 1).toFloat()
            val y = size.height -
                ((value - minValue) / range).toFloat() *
                size.height

            if (index == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }

        drawPath(
            path,
            color = color,
            style = androidx.compose.ui.graphics.drawscope.Stroke(
                width = 3f
            )
        )
    }
}
