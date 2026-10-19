package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalLayoutDirection
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

private val Navy = Color(0xFF12213A)
private val Blue = Color(0xFF2563EB)
private val Page = Color(0xFFF3F6FB)
private val Green = Color(0xFF14845C)
private val Red = Color(0xFFD14343)
private val Gray = Color(0xFF68758A)

private const val REFRESH_MS = 15_000L
private const val GOLD_URL = "https://tradingeconomics.com/commodity/gold"
private const val BTC_URL =
    "https://api.coingecko.com/api/v3/simple/price?ids=bitcoin%2Ctether&vs_currencies=usd"

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
    val price: String,
    val unit: String,
    val source: String,
    val status: String = "",
    val history: List<Float> = emptyList()
)

private fun number(value: Double, digits: Int = 2): String {
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)
}

private fun getText(urlString: String): String =
    (URL(urlString).openConnection() as HttpURLConnection).run {
        connectTimeout = 12_000
        readTimeout = 12_000
        requestMethod = "GET"
        setRequestProperty(
            "User-Agent",
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
        )
        setRequestProperty("Accept", "text/html,application/json,*/*")
        try {
            inputStream.bufferedReader().use { it.readText() }
        } finally {
            disconnect()
        }
    }

private fun fetchGoldOunce(): Double {
    // تنها منبع مجاز اونس در این برنامه، صفحه Trading Economics است.
    val html = getText(GOLD_URL)

    val patterns = listOf(
        Regex(
            """id=["'](?:(?:instrument-price-last)|(?:last))["'][^>]*>\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """class=["'][^"']*instrument-price-last[^"']*["'][^>]*>\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """"last(?:_price)?["']?\s*:\s*["']?([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
    )

    for (pattern in patterns) {
        val match = pattern.find(html) ?: continue
        val value = match.groupValues[1].replace(",", "").toDoubleOrNull()
        if (value != null && value in 100.0..100_000.0) {
            return value
        }
    }

    throw IllegalStateException(
        "قیمت از صفحه Trading Economics استخراج نشد؛ احتمالاً ساختار صفحه تغییر کرده یا دسترسی محدود است."
    )
}

private fun fetchCrypto(): Pair<Double?, Double?> {
    val json = JSONObject(getText(BTC_URL))
    val bitcoin = json.optJSONObject("bitcoin")?.optDouble("usd")
        ?.takeIf { it.isFinite() && it > 0 }
    val tether = json.optJSONObject("tether")?.optDouble("usd")
        ?.takeIf { it.isFinite() && it > 0 }
    return bitcoin to tether
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("هنوز به‌روزرسانی نشده") }
    var refreshNumber by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            loading = true

            val oldHistory = quotes.associate { it.id to it.history }

            val goldResult = withContext(Dispatchers.IO) {
                runCatching { fetchGoldOunce() }
            }

            val cryptoResult = withContext(Dispatchers.IO) {
                runCatching { fetchCrypto() }
            }

            val gold = goldResult.getOrNull()
            val crypto = cryptoResult.getOrNull()
            val timestamp = System.currentTimeMillis()

            fun history(id: String, value: Double?): List<Float> {
                val previous = oldHistory[id].orEmpty()
                if (value == null) return previous
                return (previous + value.toFloat()).takeLast(96)
            }

            val next = mutableListOf<Quote>()

            next += Quote(
                id = "usd",
                title = "دلار آزاد",
                flag = "🇺🇸",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع معتبر نرخ بازار ایران هنوز متصل نشده",
                status = "بدون داده"
            )

            next += Quote(
                id = "remittance",
                title = "حواله دلار",
                flag = "💱",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع حواله هنوز متصل نشده",
                status = "بدون داده"
            )

            next += Quote(
                id = "aed",
                title = "نرخ مرجع دلار به درهم",
                flag = "🇦🇪",
                price = "1 USD ≈ 3.6725 AED",
                unit = "نرخ مرجع",
                source = "نرخ پیوندی مرجع؛ نه قیمت خریدوفروش بازار ایران",
                status = "نرخ مرجع"
            )

            next += Quote(
                id = "herat",
                title = "دلار هرات",
                flag = "🇦🇫",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع بازار هرات هنوز متصل نشده",
                status = "بدون داده"
            )

            next += Quote(
                id = "iraq",
                title = "بازار عراق",
                flag = "🇮🇶",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع بازار عراق هنوز متصل نشده",
                status = "بدون داده"
            )

            next += Quote(
                id = "gold",
                title = "اونس جهانی طلا",
                flag = "🥇",
                price = gold?.let { "$" + number(it) }
                    ?: "قیمت قابل استخراج نیست",
                unit = "دلار / اونس",
                source = GOLD_URL,
                status = if (gold != null) "دریافت شد" else
                    (goldResult.exceptionOrNull()?.message ?: "خطای دریافت"),
                history = history("gold", gold)
            )

            next += Quote(
                id = "btc",
                title = "بیت‌کوین",
                flag = "₿",
                price = crypto?.first?.let { "$" + number(it, 2) }
                    ?: "داده در دسترس نیست",
                unit = "دلار",
                source = "CoinGecko",
                status = if (crypto?.first != null) "دریافت شد" else "خطای دریافت",
                history = history("btc", crypto?.first)
            )

            next += Quote(
                id = "usdt",
                title = "تتر",
                flag = "₮",
                price = crypto?.second?.let { "$" + number(it, 4) }
                    ?: "داده در دسترس نیست",
                unit = "دلار",
                source = "CoinGecko؛ قیمت دلاری، نه نرخ تتر به تومان",
                status = if (crypto?.second != null) "دریافت شد" else "خطای دریافت",
                history = history("usdt", crypto?.second)
            )

            quotes = next
            lastUpdated = SimpleDateFormat(
                "HH:mm:ss",
                Locale("fa", "IR")
            ).format(Date(timestamp))

            refreshNumber++
            loading = false
            delay(REFRESH_MS)
        }
    }

    Scaffold(
        topBar = {
            Surface(color = Navy) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            "ARZ-NET",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "رصد بازار ارز و طلا",
                            color = Color(0xFFD6E2F5),
                            fontSize = 13.sp
                        )
                    }
                    IconButton(
                        onClick = { refreshNumber++ },
                        enabled = !loading
                    ) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "به‌روزرسانی",
                            tint = Color.White
                        )
                    }
                }
            }
        },
        containerColor = Page
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Surface(
                    color = Color.White,
                    shape = RoundedCornerShape(18.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "داشبورد بازار",
                            fontSize = 19.sp,
                            fontWeight = FontWeight.Bold,
                            color = Navy
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "آخرین بررسی: $lastUpdated",
                            color = Gray,
                            fontSize = 13.sp
                        )
                        Text(
                            if (loading) "در حال دریافت اطلاعات…" else
                                "بروزرسانی خودکار هر ۱۵ ثانیه",
                            color = Blue,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "نکته: قیمت‌های فاقد منبع معتبر عمداً نمایش داده نمی‌شوند.",
                            fontSize = 12.sp,
                            color = Gray
                        )
                    }
                }
            }

            items(quotes, key = { it.id }) { quote ->
                QuoteCard(quote)
            }

            item {
                Text(
                    "نسخه آزمایشی — برای نرخ‌های بازار ایران باید منبع داده معتبر متصل شود.",
                    color = Gray,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp)
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(quote: Quote) {
    Surface(
        color = Color.White,
        shape = RoundedCornerShape(18.dp),
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 1.dp
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(quote.flag, fontSize = 28.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        quote.title,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Navy
                    )
                    Text(quote.unit, fontSize = 12.sp, color = Gray)
                }
                Text(
                    quote.price,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (quote.status == "دریافت شد") Green else Navy,
                    textAlign = TextAlign.End
                )
            }

            if (quote.history.size > 1) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "نمودار نمونه از زمان اجرای برنامه",
                    fontSize = 11.sp,
                    color = Gray
                )
                Spacer(Modifier.height(6.dp))
                MiniChart(quote.history)
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Page)
            Spacer(Modifier.height(8.dp))
            Text(
                "منبع: ${quote.source}",
                fontSize = 11.sp,
                color = Gray
            )
            if (quote.status.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "وضعیت: ${quote.status}",
                    fontSize = 11.sp,
                    color = if (quote.status == "دریافت شد") Green else Red
                )
            }
        }
    }
}

@Composable
private fun MiniChart(values: List<Float>) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
    ) {
        if (values.size < 2) return@Canvas

        val min = values.minOrNull() ?: return@Canvas
        val max = values.maxOrNull() ?: return@Canvas
        val range = (max - min).takeIf { it > 0f } ?: 1f

        val path = Path()
        values.forEachIndexed { index, value ->
            val x = size.width * index / (values.size - 1).toFloat()
            val y = size.height -
                ((value - min) / range * (size.height - 4f)) - 2f

            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        drawPath(
            path = path,
            color = Blue,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 3f)
        )
    }
}
