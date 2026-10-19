package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.coroutineScope
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

// 1 USD = 3.6725 AED
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
    val status: String
)

private fun fmt(value: Double, digits: Int = 0): String {
    return NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)
}

/*
 * ورودی و خروجی هر دو بر حسب تومان هستند.
 * قیمت هر درهم × تعداد درهم معادل یک دلار = قیمت دلار محاسباتی
 */
private fun calculateUsdFromAed(aedPriceToman: Double): Double {
    return aedPriceToman * AED_PER_USD
}

private fun calculateAedFromUsd(usdPriceToman: Double): Double {
    return usdPriceToman / AED_PER_USD
}

private fun readUrl(address: String): String {
    val connection = URL(address).openConnection() as HttpURLConnection

    connection.connectTimeout = 10_000
    connection.readTimeout = 10_000
    connection.requestMethod = "GET"
    connection.instanceFollowRedirects = true
    connection.setRequestProperty(
        "User-Agent",
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/131.0 Mobile Safari/537.36"
    )
    connection.setRequestProperty("Cache-Control", "no-cache")
    connection.setRequestProperty("Pragma", "no-cache")

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
            source = item.optString(
                "source",
                "Iran Market Data"
            ),
            timestamp = item.optString(
                "timestamp_tehran",
                item.optString("timestamp", "")
            ),
            stale = item.optBoolean("stale", false)
        )
    }

    return result
}

/*
 * هر دو فایل بررسی می‌شوند.
 * popular.json برای نمادهای منتخب است؛ latest-toman.json
 * برای یافتن نمادهایی مانند درهم که در فایل منتخب نیستند.
 */
private fun fetchAllPrices(): List<MarketPrice> {
    val popularResult = runCatching {
        parsePrices(readUrl(MARKET_URL))
    }

    val allResult = runCatching {
        parsePrices(readUrl(ALL_MARKET_URL))
    }

    val popular = popularResult.getOrDefault(emptyList())
    val all = allResult.getOrDefault(emptyList())

    // داده‌های فایل منتخب بر داده تکراری فایل کامل اولویت دارند.
    val combined = (all + popular)
        .associateBy { it.symbol.uppercase(Locale.ROOT) }
        .values
        .toList()

    if (combined.isEmpty()) {
        throw IllegalStateException(
            "دریافت هر دو منبع قیمت ناموفق بود: " +
                (popularResult.exceptionOrNull()?.message ?: "") +
                " | " +
                (allResult.exceptionOrNull()?.message ?: "")
        )
    }

    return combined
}

private fun findPrice(
    prices: List<MarketPrice>,
    vararg symbols: String
): MarketPrice? {
    for (symbol in symbols) {
        prices.firstOrNull {
            it.symbol.equals(symbol, ignoreCase = true)
        }?.let { return it }
    }
    return null
}

private fun findAed(prices: List<MarketPrice>): MarketPrice? {
    // فقط نمادهای مشخص درهم پذیرفته می‌شوند.
    val exact = findPrice(
        prices,
        "AED_IRR_FREE",
        "AED_IRT_FREE",
        "AED_IRR",
        "AED_IRT"
    )

    if (exact != null) return exact

    return prices.firstOrNull {
        val symbol = it.symbol.uppercase(Locale.ROOT)
        val name = it.name.lowercase(Locale.ROOT)

        symbol.startsWith("AED_") &&
            (
                symbol.contains("FREE") ||
                symbol.contains("IRR") ||
                symbol.contains("IRT")
            ) &&
            name.contains("درهم")
    }
}

/*
 * اونس فقط از Trading Economics خوانده می‌شود.
 * هیچ منبع جایگزینی برای اونس استفاده نمی‌شود.
 */
private fun fetchGoldOunce(): Double {
    val html = readUrl(GOLD_URL)

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
            """instrument-price-last.{0,200}?([\d,]{1,8}(?:\.\d+)?)""",
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
        "قیمت اونس از صفحه Trading Economics استخراج نشد؛ ممکن است صفحه پویا باشد"
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
            id = id,
            title = title,
            flag = flag,
            price = "داده در دسترس نیست",
            unit = unit,
            source = "Iran Market Data",
            status = "نماد معتبر این قیمت در داده‌های دریافتی پیدا نشد"
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
            append(
                if (market.stale) {
                    "هشدار: منبع داده را قدیمی اعلام کرده"
                } else {
                    "داده دریافت شد"
                }
            )

            if (market.timestamp.isNotBlank()) {
                append(" | ")
                append(market.timestamp)
            }
        }
    )
}

private fun buildMarketQuotes(
    prices: List<MarketPrice>
): List<Quote> {
    val usd = findPrice(prices, "USD_IRR_FREE")
    val aed = findAed(prices)
    val gold18 = findPrice(prices, "GOLD_18K_IRR")
    val tether = findPrice(prices, "USDT_IRR")

    // هر درهم به تومان × 3.6725 = دلار محاسباتی به تومان
    val calculatedUsd = aed?.let {
        calculateUsdFromAed(it.price)
    }

    val result = mutableListOf<Quote>()

    result += quoteFromMarket(
        "usd",
        "دلار آزاد",
        "🇺🇸",
        usd
    )

    result += quoteFromMarket(
        "aed",
        "درهم بازار",
        "🇦🇪",
        aed
    )

    result += Quote(
        id = "usd_calc",
        title = "دلار محاسباتی از درهم",
        flag = "🧮",
        price = calculatedUsd?.let { fmt(it) }
            ?: "قابل محاسبه نیست",
        unit = "تومان",
        source = "قیمت درهم بازار × 3.6725",
        status = if (calculatedUsd != null) {
            "محاسبه بر اساس قیمت دریافت‌شده درهم"
        } else {
            "برای محاسبه، قیمت معتبر درهم لازم است"
        }
    )

    val diffPercent = if (
        usd != null &&
        calculatedUsd != null &&
        calculatedUsd > 0
    ) {
        (usd.price - calculatedUsd) / calculatedUsd * 100.0
    } else {
        null
    }

    result += Quote(
        id = "diff",
        title = "اختلاف دلار مستقیم با محاسباتی",
        flag = "📊",
        price = diffPercent?.let { "${fmt(it, 2)}٪" }
            ?: "قابل محاسبه نیست",
        unit = "درصد",
        source = "مقایسه دلار آزاد با دلار محاسباتی از درهم",
        status = "اختلاف درصدی؛ نه نرخ خریدوفروش"
    )

    result += quoteFromMarket(
        "gold18",
        "طلای ۱۸ عیار (هر گرم)",
        "🪙",
        gold18
    )

    result += quoteFromMarket(
        "usdt",
        "تتر بازار ایران",
        "₮",
        tether
    )

    result += Quote(
        id = "herat",
        title = "دلار هرات",
        flag = "🇦🇫",
        price = "داده در دسترس نیست",
        unit = "تومان",
        source = "منبع مستقل بازار هرات متصل نشده",
        status = "از نرخ رسمی ارز به‌تنهایی قابل تعیین نیست"
    )

    result += Quote(
        id = "iraq",
        title = "بازار عراق",
        flag = "🇮🇶",
        price = "داده در دسترس نیست",
        unit = "تومان",
        source = "منبع مستقل بازار عراق متصل نشده",
        status = "برای قیمت بازار به داده واقعی نیاز است"
    )

    return result
}

private fun buildGoldQuote(result: Result<Double>): Quote {
    val price = result.getOrNull()

    return Quote(
        id = "ounce",
        title = "اونس جهانی طلا",
        flag = "🥇",
        price = price?.let { "$" + fmt(it, 2) }
            ?: "قیمت قابل استخراج نیست",
        unit = "دلار / اونس تروا",
        source = GOLD_URL,
        status = if (price != null) {
            "دریافت از Trading Economics"
        } else {
            result.exceptionOrNull()?.message
                ?: "استخراج قیمت ناموفق بود"
        }
    )
}

private fun mergeQuotes(
    old: List<Quote>,
    updates: List<Quote>
): List<Quote> {
    val updateMap = updates.associateBy { it.id }
    val oldIds = old.map { it.id }.toSet()

    val mergedOld = old.map { quote ->
        updateMap[quote.id] ?: quote
    }

    val newItems = updates.filter { it.id !in oldIds }

    return mergedOld + newItems
}

private fun nowTime(): String {
    return SimpleDateFormat(
        "HH:mm:ss",
        Locale("fa", "IR")
    ).format(Date())
}

@Composable
private fun ArzNetScreen() {
    var quotes by remember { mutableStateOf<List<Quote>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var lastUpdated by remember { mutableStateOf("هنوز دریافت نشده") }
    var marketUpdated by remember { mutableStateOf("هنوز دریافت نشده") }
    var goldUpdated by remember { mutableStateOf("هنوز دریافت نشده") }
    var refreshKey by remember { mutableIntStateOf(0) }
    var errorMessage by remember { mutableStateOf("") }

    LaunchedEffect(refreshKey) {
        while (true) {
            loading = true
            errorMessage = ""

            /*
             * بازار و اونس هم‌زمان درخواست می‌شوند.
             * هر کدام زودتر برسد، همان بخش صفحه به‌روزرسانی می‌شود.
             */
            coroutineScope {
                val marketJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { fetchAllPrices() }
                    }

                    val prices = result.getOrDefault(emptyList())

                    if (prices.isNotEmpty()) {
                        quotes = mergeQuotes(
                            quotes,
                            buildMarketQuotes(prices)
                        )
                        marketUpdated = nowTime()
                    } else {
                        errorMessage =
                            "خطای دریافت بازار: " +
                                (
                                    result.exceptionOrNull()?.message
                                        ?: "خطای نامشخص"
                                )
                    }
                }

                val goldJob = launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { fetchGoldOunce() }
                    }

                    quotes = mergeQuotes(
                        quotes,
                        listOf(buildGoldQuote(result))
                    )
                    goldUpdated = nowTime()
                }

                joinAll(marketJob, goldJob)
            }

            lastUpdated = nowTime()
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

                        Spacer(Modifier.height(8.dp))

                        Text(
                            "آخرین تکمیل بررسی: $lastUpdated",
                            color = Gray,
                            fontSize = 12.sp
                        )

                        Text(
                            "آخرین دریافت بازار: $marketUpdated",
                            color = Gray,
                            fontSize = 12.sp
                        )

                        Text(
                            "آخرین بررسی اونس: $goldUpdated",
                            color = Gray,
                            fontSize = 12.sp
                        )

                        Spacer(Modifier.height(5.dp))

                        Text(
                            if (loading) {
                                "در حال بررسی منابع قیمت…"
                            } else {
                                "بررسی خودکار هر ۱۵ ثانیه"
                            },
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
    val isError =
        quote.price.contains("نیست") ||
        quote.price.contains("نمی‌شود") ||
        quote.price.contains("استخراج") ||
        quote.status.contains("خطا") ||
        quote.status.contains("لازم است") ||
        quote.status.contains("متصل نشده") ||
        quote.status.contains("ناموفق")

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
                Text(
                    quote.flag,
                    fontSize = 27.sp
                )

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
                    color = if (isError) Red else Green,
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
                color = if (isError) Red else Gray,
                fontSize = 11.sp
            )
        }
    }
}
