
package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
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

private const val REFRESH_MS = 15_000L
private const val MARKET_URL =
    "https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/latest-toman.json"
private const val GOLD_URL = "https://tradingeconomics.com/commodity/gold"
private const val AED_PER_USD = 3.6725

private val Navy = Color(0xFF12213A)
private val Blue = Color(0xFF2563EB)
private val Page = Color(0xFFF3F6FB)
private val Green = Color(0xFF14845C)
private val Red = Color(0xFFD14343)
private val Gray = Color(0xFF68758A)

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

private data class MarketPrice(
    val price: Double,
    val source: String,
    val timestamp: String,
    val stale: Boolean
)

private data class Quote(
    val id: String,
    val title: String,
    val flag: String,
    val price: String,
    val unit: String,
    val source: String,
    val status: String,
    val history: List<Float> = emptyList()
)

private fun fmt(value: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)

private fun getText(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection
    connection.connectTimeout = 12000
    connection.readTimeout = 12000
    connection.requestMethod = "GET"
    connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    )
    connection.setRequestProperty("Accept", "application/json,text/html,*/*")

    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
                ?: throw IllegalStateException("HTTP $code")
        }
        val body = stream.bufferedReader().use { it.readText() }
        if (code !in 200..299) {
            throw IllegalStateException("خطای HTTP $code")
        }
        return body
    } finally {
        connection.disconnect()
    }
}

private fun parseMarketPrices(jsonText: String): List<Pair<String, MarketPrice>> {
    val root = JSONObject(jsonText)
    val array = root.optJSONArray("data")
        ?: throw IllegalStateException("فهرست قیمت‌ها در پاسخ پیدا نشد")

    val result = mutableListOf<Pair<String, MarketPrice>>()

    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val symbol = item.optString("symbol")
        val price = item.optDouble("price", Double.NaN)

        if (symbol.isBlank() || !price.isFinite() || price <= 0) continue

        result += symbol to MarketPrice(
            price = price,
            source = item.optString("source", "Iran Market Data"),
            timestamp = item.optString(
                "timestamp_tehran",
                item.optString("timestamp", "")
            ),
            stale = item.optBoolean("stale", false)
        )
    }

    return result
}

private fun fetchGoldOunce(): Double {
    // اونس فقط از صفحه Trading Economics دریافت می‌شود.
    val html = getText(GOLD_URL)

    val patterns = listOf(
        Regex(
            """id=["']instrument-price-last["'][^>]*>\s*([\d,]+(?:\.\d+)?)""",
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
        val value = match.groupValues[1]
            .replace(",", "")
            .toDoubleOrNull()

        if (value != null && value in 100.0..100000.0) {
            return value
        }
    }

    throw IllegalStateException(
        "قیمت از صفحه Trading Economics استخراج نشد"
    )
}

private fun marketQuote(
    id: String,
    title: String,
    flag: String,
    price: MarketPrice?,
    unit: String = "تومان"
): Quote {
    if (price == null) {
        return Quote(
            id, title, flag, "داده در دسترس نیست", unit,
            "Iran Market Data", "نماد قیمت پیدا نشد"
        )
    }

    if (price.stale) {
        return Quote(
            id, title, flag, fmt(price.price), unit,
            price.source, "داده قدیمی است؛ با احتیاط استفاده شود"
        )
    }

    return Quote(
        id, title, flag, fmt(price.price), unit,
        price.source,
        if (price.timestamp.isBlank()) "قیمت دریافت شد"
        else "آخرین زمان ثبت: ${price.timestamp}"
    )
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("هنوز دریافت نشده") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var lastError by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        while (true) {
            loading = true
            lastError = ""

            val marketResult = withContext(Dispatchers.IO) {
                runCatching {
                    parseMarketPrices(getText(MARKET_URL))
                }
            }

            val goldResult = withContext(Dispatchers.IO) {
                runCatching { fetchGoldOunce() }
            }

            val prices = marketResult.getOrNull().orEmpty()
            val map = prices.toMap()

            val usd = map["USD_IRR_FREE"]

            // نام نماد درهم ممکن است در فهرست نمادها متفاوت باشد.
            // فقط نماد دارای نام معتبر «درهم» پذیرفته می‌شود.
            val aed = prices.firstOrNull { (symbol, _) ->
                val s = symbol.uppercase(Locale.ROOT)
                s.contains("AED") &&
                    (s.contains("IRR") || s.contains("IRT") ||
                     s.contains("FREE"))
            }?.second

            val calculatedUsd = aed?.let { it.price / AED_PER_USD }

            val gold = goldResult.getOrNull()

            val goldToman = if (gold != null && usd != null) {
                gold * usd.price / AED_PER_USD * 0.0 +
                    gold * usd.price / 1.0
            } else {
                null
            }

            val next = mutableListOf<Quote>()

            next += marketQuote(
                "usd", "دلار آزاد", "🇺🇸", usd
            )

            next += marketQuote(
                "aed", "درهم بازار آزاد", "🇦🇪", aed
            )

            next += Quote(
                id = "usd_calc",
                title = "دلار محاسباتی از درهم",
                flag = "🧮",
                price = calculatedUsd?.let { fmt(it) }
                    ?: "قابل محاسبه نیست",
                unit = "تومان",
                source = "قیمت درهم بازار آزاد ÷ $AED_PER_USD",
                status = if (calculatedUsd != null)
                    "برآورد محاسباتی؛ نه نرخ مستقل بازار"
                else
                    "برای محاسبه، قیمت معتبر درهم لازم است"
            )

            val difference = if (
                usd != null && calculatedUsd != null
            ) {
                (usd.price - calculatedUsd) / calculatedUsd * 100.0
            } else null

            next += Quote(
                id = "diff",
                title = "اختلاف دلار مستقیم و محاسباتی",
                flag = "📊",
                price = difference?.let { "${fmt(it, 2)}٪" }
                    ?: "قابل محاسبه نیست",
                unit = "درصد",
                source = "مقایسه نرخ دلار آزاد با برآورد درهم",
                status = "اختلاف مثبت یعنی دلار مستقیم بالاتر است"
            )

            next += Quote(
                id = "herat",
                title = "دلار هرات",
                flag = "🇦🇫",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع مستقل و معتبر هرات متصل نشده",
                status = "قیمت از روی درهم قابل استنتاج نیست"
            )

            next += Quote(
                id = "iraq",
                title = "بازار عراق",
                flag = "🇮🇶",
                price = "داده در دسترس نیست",
                unit = "تومان",
                source = "منبع مستقل و معتبر بازار عراق متصل نشده",
                status = "قیمت مستقل بازار لازم است"
            )

            next += Quote(
                id = "gold",
                title = "اونس جهانی طلا",
                flag = "🥇",
                price = gold?.let { "$" + fmt(it, 2) }
                    ?: "قیمت قابل استخراج نیست",
                unit = "دلار / اونس",
                source = GOLD_URL,
                status = if (gold != null) "از صفحه دریافت شد"
                else goldResult.exceptionOrNull()?.message
                    ?: "استخراج قیمت ناموفق بود"
            )

            next += marketQuote(
                "gold18", "طلای ۱۸ عیار", "🪙",
                map["GOLD_18K_IRR"]
            )

            next += marketQuote(
                "usdt", "تتر بازار ایران", "₮",
                map["USDT_IRR"]
            )

            quotes = next

            if (marketResult.isFailure) {
                lastError = "دریافت اطلاعات بازار ایران ناموفق بود: " +
                    (marketResult.exceptionOrNull()?.message ?: "خطای نامشخص")
            }

            lastUpdated = SimpleDateFormat(
                "HH:mm:ss",
                Locale("fa", "IR")
            ).format(Date())

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
                        .padding(18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            "ARZ-NET",
                            color = Color.White,
                            fontSize = 24.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "داشبورد بازار ارز و طلا",
                            color = Color(0xFFD6E2F5),
                            fontSize = 13.sp
                        )
                    }

                    IconButton(
                        onClick = { refreshKey++ },
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
                            "وضعیت بازار",
                            color = Navy,
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        Text("آخرین بررسی: $lastUpdated", color = Gray)
                        Text(
                            if (loading) "در حال دریافت قیمت‌ها…" else
                                "بررسی خودکار هر ۱۵ ثانیه",
                            color = Blue,
                            fontSize = 12.sp
                        )

                        if (lastError.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                lastError,
                                color = Red,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            items(quotes, key = { it.id }) { quote ->
                QuoteCard(quote)
            }

            item {
                Text(
                    "قیمت‌های بازار ممکن است با تأخیر منتشر شوند. " +
                        "نرخ محاسباتی درهم جایگزین قیمت مستقل بازار نیست.",
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
                Text(quote.flag, fontSize = 27.sp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        quote.title,
                        color = Navy,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(quote.unit, color = Gray, fontSize = 12.sp)
                }
                Text(
                    quote.price,
                    color = if (
                        quote.status.contains("دریافت شد") ||
                        quote.status.contains("ثبت")
                    ) Green else Navy,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Page)
            Spacer(Modifier.height(8.dp))

            Text("منبع: ${quote.source}", color = Gray, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text(
                "وضعیت: ${quote.status}",
                color = if (
                    quote.status.contains("ناموفق") ||
                    quote.status.contains("نیست") ||
                    quote.status.contains("لازم است")
                ) Red else Gray,
                fontSize = 11.sp
            )
        }
    }
}
