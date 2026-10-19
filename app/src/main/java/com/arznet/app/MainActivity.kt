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
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val REFRESH_MS = 15_000L
private const val AED_PER_USD = 3.6725

private const val MARKET_URL =
    "https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/popular.json"

private const val ALL_MARKET_URL =
    "https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/latest-toman.json"

private const val GOLD_URL =
    "https://tradingeconomics.com/commodity/gold"

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
    val symbol: String,
    val name: String,
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

private fun fmt(value: Double, digits: Int = 0): String {
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)
}

private fun readUrl(address: String): String {
    val connection =
        URL(address).openConnection() as HttpURLConnection

    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    connection.requestMethod = "GET"
    connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    )
    connection.setRequestProperty("Cache-Control", "no-cache")

    try {
        val code = connection.responseCode
        if (code !in 200..299) {
            throw IllegalStateException("HTTP $code")
        }

        return connection.inputStream
            .bufferedReader()
            .use { it.readText() }
    } finally {
        connection.disconnect()
    }
}

private fun parsePriceValue(value: Any?): Double {
    return when (value) {
        is Number -> value.toDouble()
        is String -> value
            .replace(",", "")
            .trim()
            .toDoubleOrNull() ?: Double.NaN
        else -> Double.NaN
    }
}

private fun parsePrices(jsonText: String): List<MarketPrice> {
    val root = JSONObject(jsonText)

    val data: JSONArray = when (val d = root.opt("data")) {
        is JSONArray -> d
        is JSONObject -> {
            val array = JSONArray()
            val keys = d.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val item = d.optJSONObject(key)
                if (item != null) {
                    if (!item.has("symbol")) {
                        item.put("symbol", key)
                    }
                    array.put(item)
                }
            }
            array
        }
        else -> throw IllegalStateException(
            "ساختار JSON قیمت‌ها شناخته نشد"
        )
    }

    val result = mutableListOf<MarketPrice>()

    for (i in 0 until data.length()) {
        val item = data.optJSONObject(i) ?: continue

        val symbol = item.optString("symbol").trim()
        val name = item.optString(
            "name_fa",
            item.optString("name", symbol)
        )

        val price = parsePriceValue(item.opt("price"))

        if (
            symbol.isBlank() ||
            !price.isFinite() ||
            price <= 0
        ) continue

        result += MarketPrice(
            symbol = symbol,
            name = name,
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

private fun fetchAllPrices(): List<MarketPrice> {
    // ابتدا از فایل منتخب استفاده می‌کنیم.
    val popular = runCatching {
        parsePrices(readUrl(MARKET_URL))
    }.getOrDefault(emptyList())

    // فایل منتخب ممکن است درهم را نداشته باشد.
    // در صورت نیاز، فهرست کامل قیمت‌ها را بررسی می‌کنیم.
    val required = setOf(
        "USD_IRR_FREE",
        "GOLD_18K_IRR",
        "USDT_IRR"
    )

    if (popular.map { it.symbol }.containsAll(required)) {
        return popular
    }

    val all = parsePrices(readUrl(ALL_MARKET_URL))
    return (popular + all).associateBy { it.symbol }.values.toList()
}

private fun findPrice(
    prices: List<MarketPrice>,
    vararg symbols: String
): MarketPrice? {
    for (symbol in symbols) {
        prices.firstOrNull { it.symbol.equals(symbol, true) }
            ?.let { return it }
    }
    return null
}

private fun findAed(prices: List<MarketPrice>): MarketPrice? {
    // فقط شناسه‌های صریح درهم آزاد پذیرفته می‌شوند.
    return findPrice(
        prices,
        "AED_IRR_FREE",
        "AED_IRT_FREE",
        "AED_IRR",
        "AED_IRT"
    ) ?: prices.firstOrNull {
        val symbol = it.symbol.uppercase(Locale.ROOT)
        val name = it.name.lowercase(Locale.ROOT)

        symbol.contains("AED") &&
            (
                symbol.contains("FREE") ||
                symbol.contains("IRR") ||
                symbol.contains("IRT") ||
                name.contains("درهم")
            )
    }
}

private fun fetchGoldOunce(): Double {
    // اونس فقط از صفحه تعیین‌شده دریافت می‌شود.
    val html = readUrl(GOLD_URL)

    val patterns = listOf(
        Regex(
            """id=["']instrument-price-last["'][^>]*>\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            """class=["'][^"']*instrument-price-last[^"']*["'][^>]*>\s*([\d,]+(?:\.\d+)?)""",
            RegexOption.IGNORE_CASE
        )
    )

    for (pattern in patterns) {
        val match = pattern.find(html) ?: continue
        val price = match.groupValues[1]
            .replace(",", "")
            .toDoubleOrNull()

        if (price != null && price in 100.0..100000.0) {
            return price
        }
    }

    throw IllegalStateException(
        "قیمت اونس از صفحه Trading Economics استخراج نشد"
    )
}

private fun quoteFromMarket(
    id: String,
    title: String,
    flag: String,
    market: MarketPrice?,
    unit: String = "تومان"
): Quote {
    if (market == null) {
        return Quote(
            id, title, flag, "داده در دسترس نیست", unit,
            "Iran Market Data",
            "نماد این قیمت در پاسخ سرویس پیدا نشد"
        )
    }

    return Quote(
        id = id,
        title = title,
        flag = flag,
        price = fmt(market.price),
        unit = unit,
        source = market.source,
        status = buildString {
            append(if (market.stale) "هشدار: داده قدیمی است" else "قیمت دریافت شد")
            if (market.timestamp.isNotBlank()) {
                append(" | ")
                append(market.timestamp)
            }
        }
    )
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("هنوز دریافت نشده") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        while (true) {
            loading = true
            errorMessage = ""

            val marketResult = withContext(Dispatchers.IO) {
                runCatching { fetchAllPrices() }
            }

            val goldResult = withContext(Dispatchers.IO) {
                runCatching { fetchGoldOunce() }
            }

            val prices = marketResult.getOrDefault(emptyList())

            val usd = findPrice(prices, "USD_IRR_FREE")
            val gold18 = findPrice(prices, "GOLD_18K_IRR")
            val tether = findPrice(prices, "USDT_IRR")
            val aed = findAed(prices)

            // واحد API برابر تومان است؛ نرخ درهم نیز باید تومان باشد.
            val calculatedUsd = aed?.price?.div(AED_PER_USD)

            val next = mutableListOf<Quote>()

            next += quoteFromMarket(
                "usd",
                "دلار آزاد",
                "🇺🇸",
                usd
            )

            next += quoteFromMarket(
                "aed",
                "درهم آزاد",
                "🇦🇪",
                aed
            )

            next += Quote(
                id = "usd_calc",
                title = "دلار محاسباتی از درهم",
                flag = "🧮",
                price = calculatedUsd?.let { fmt(it) }
                    ?: "قابل محاسبه نیست",
                unit = "تومان",
                source = "قیمت درهم بازار آزاد ÷ 3.6725",
                status = if (calculatedUsd != null) {
                    "برآورد برابری ارز؛ مستقل از نرخ مستقیم بازار"
                } else {
                    "قیمت معتبر درهم در منبع پیدا نشد"
                }
            )

            val diffPercent = if (
                usd != null && calculatedUsd != null &&
                calculatedUsd > 0
            ) {
                (usd.price - calculatedUsd) /
                    calculatedUsd * 100.0
            } else {
                null
            }

            next += Quote(
                id = "diff",
                title = "اختلاف دلار مستقیم با محاسباتی",
                flag = "📊",
                price = diffPercent?.let { "${fmt(it, 2)}٪" }
                    ?: "قابل محاسبه نیست",
                unit = "درصد",
                source = "مقایسه دلار آزاد با قیمت محاسباتی درهم",
                status = "اختلاف درصدی؛ نه نرخ خریدوفروش"
            )

            next += quoteFromMarket(
                "gold18",
                "طلای ۱۸ عیار (هر گرم)",
                "🪙",
                gold18
            )

            next += quoteFromMarket(
                "usdt",
                "تتر بازار ایران",
                "₮",
                tether
            )

            val goldOunce = goldResult.getOrNull()

            next += Quote(
                id = "ounce",
                title = "اونس جهانی طلا",
                flag = "🥇",
                price = goldOunce?.let { "$" + fmt(it, 2) }
                    ?: "قیمت قابل استخراج نیست",
                unit = "دلار / اونس",
                source = GOLD_URL,
                status = if (goldOunce != null) {
                    "از صفحه Trading Economics دریافت شد"
                } else {
                    goldResult.exceptionOrNull()?.message
                        ?: "استخراج قیمت ناموفق بود"
                }
            )

            // قیمت این دو بازار را از نرخ درهم حدس نمی‌زنیم.
            next += Quote(
                id = "herat",
                title = "دلار هرات",
                flag = "🇦🇫",
                price = "منبع مستقل لازم است",
                unit = "تومان",
                source = "منبع معتبر قیمت هرات هنوز متصل نشده",
                status = "از نرخ درهم به‌تنهایی قابل محاسبه نیست"
            )

            next += Quote(
                id = "iraq",
                title = "بازار عراق",
                flag = "🇮🇶",
                price = "منبع مستقل لازم است",
                unit = "تومان",
                source = "منبع معتبر بازار عراق هنوز متصل نشده",
                status = "برای محاسبه، نرخ واقعی بازار لازم است"
            )

            quotes = next

            if (marketResult.isFailure) {
                errorMessage =
                    "خطای دریافت قیمت بازار: " +
                    (marketResult.exceptionOrNull()?.message
                        ?: "خطای نامشخص")
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
                        enabled = !loading,
                        onClick = { refreshKey++ }
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
                            "داشبورد قیمت‌ها",
                            color = Navy,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "آخرین بررسی: $lastUpdated",
                            color = Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            if (loading) "در حال دریافت قیمت‌ها…" else
                                "بررسی خودکار هر ۱۵ ثانیه",
                            color = Blue,
                            fontSize = 12.sp
                        )
                        if (errorMessage.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(
                                errorMessage,
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
                    Text(
                        quote.unit,
                        color = Gray,
                        fontSize = 12.sp
                    )
                }

                Text(
                    quote.price,
                    color = if (
                        quote.status.contains("دریافت شد")
                    ) Green else Navy,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.End
                )
            }

            Spacer(Modifier.height(8.dp))
            HorizontalDivider(color = Page)
            Spacer(Modifier.height(8.dp))

            Text(
                "منبع: ${quote.source}",
                color = Gray,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "وضعیت: ${quote.status}",
                color = if (
                    quote.status.contains("خطا") ||
                    quote.status.contains("لازم") ||
                    quote.status.contains("نیست")
                ) Red else Gray,
                fontSize = 11.sp
            )
        }
    }
}
