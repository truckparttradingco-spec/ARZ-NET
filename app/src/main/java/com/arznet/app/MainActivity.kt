package com.arznet.prices

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

private const val API_URL = "https://truckparttrading.com/price-list/chand/kwgt-proxy.php"
private const val MAX_PRICE_AGE_SECONDS = 60L
private const val PREFS = "arznet_display_preferences"
private const val HOME_KEYS = "home_asset_keys"
private const val MAX_HOME_CARDS = 10
private val Bg = Color(0xFF080D16)
private val Panel = Color(0xFF111B2A)
private val CardBg = Color(0xFF142237)
private val Stroke = Color(0xFF263D59)
private val Blue = Color(0xFF63B5FF)
private val White = Color(0xFFF2F6FC)
private val Muted = Color(0xFF9AADC2)
private val Green = Color(0xFF42D6A0)
private val Red = Color(0xFFFF8585)

data class Quote(
    val key: String,
    val label: String,
    val price: Double?,
    val buy: Double?,
    val sell: Double?,
    val pct: Double?,
    val timestamp: String?,
    val fresh: Boolean
)
data class PriceData(val quotes: List<Quote>, val updatedAt: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(8, 13, 22)
        window.navigationBarColor = android.graphics.Color.rgb(8, 13, 22)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = Panel, onBackground = White, onSurface = White)) {
                PricesScreen()
            }
        }
    }
}

@Composable
private fun PricesScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    var data by remember { mutableStateOf<PriceData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }
    var showHomeSettings by remember { mutableStateOf(false) }
    var showWidgetSettings by remember { mutableStateOf(false) }
    var homeKeys by remember { mutableStateOf(prefs.getString(HOME_KEYS, "").orEmpty().split(",").filter { it.isNotBlank() }.take(MAX_HOME_CARDS)) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = data == null
        try {
            val received = withContext(Dispatchers.IO) { fetchPrices() }
            data = received
            val available = received.quotes.map { it.key }.toSet()
            if (!prefs.contains(HOME_KEYS)) {
                // First launch: prioritize the ten assets requested for the home screen.
                homeKeys = defaultHomeAssetKeys(received.quotes)
                prefs.edit().putString(HOME_KEYS, homeKeys.joinToString(",")).apply()
            } else {
                homeKeys = homeKeys.filter { it in available }.take(MAX_HOME_CARDS)
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "دریافت اطلاعات ناموفق بود"
        } finally {
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) {
            delay(60_000)
            refresh()
        }
    }

    Column(
        Modifier.fillMaxSize().background(Bg).padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("ARZ-NET", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Text("بازار ارز و طلا", color = White, fontSize = 23.sp, fontWeight = FontWeight.Bold)
                Text("نرخ‌های دریافتی از منبع داده", color = Muted, fontSize = 11.sp)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { scope.launch { refresh() } }) {
                    Text(if (loading) "..." else "تازه‌سازی", color = Blue, fontWeight = FontWeight.SemiBold)
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) {
                        Text("⋮", color = White, fontSize = 27.sp, fontWeight = FontWeight.Bold)
                    }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(
                            text = { Text("انتخاب کارت‌های صفحه اصلی") },
                            onClick = { menuExpanded = false; showHomeSettings = true }
                        )
                        DropdownMenuItem(
                            text = { Text("تنظیم چهار کارت ویجت") },
                            onClick = { menuExpanded = false; showWidgetSettings = true }
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 9.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("کارت‌های صفحه اصلی: ${homeKeys.size} از ۱۰", color = Muted, fontSize = 11.sp)
            Text("HANSHA  •  ${data?.quotes?.size ?: 0} دارایی", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(10.dp))

        when {
            loading && data == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Blue)
            }
            data == null -> Column(
                Modifier.fillMaxSize(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text("دریافت قیمت‌ها انجام نشد", color = Red)
                Text(error ?: "اتصال اینترنت را بررسی کنید", color = Muted)
                TextButton(onClick = { scope.launch { refresh() } }) { Text("تلاش دوباره") }
            }
            else -> {
                val visibleQuotes = data!!.quotes.filter { it.key in homeKeys }
                if (visibleQuotes.isEmpty()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text("از منوی سه‌نقطه کارت‌های بازار را انتخاب کنید.", color = Muted, textAlign = TextAlign.Center)
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(2),
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        items(visibleQuotes, key = { it.key }) { quote -> QuoteCard(quote) }
                    }
                }
            }
        }
        data?.updatedAt?.let {
            Text("آخرین به‌روزرسانی سرور: ${formatTime(it)}", Modifier.fillMaxWidth().padding(top = 5.dp), color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center)
        }
        error?.let { if (data != null) Text("خطای تازه‌سازی: $it", color = Red, fontSize = 10.sp) }
    }

    if (showHomeSettings && data != null) {
        AlertDialog(
            onDismissRequest = { showHomeSettings = false },
            title = { Text("انتخاب ۱۰ کارت صفحه اصلی") },
            text = {
                Column {
                    Text("${homeKeys.size} کارت انتخاب شده", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(6.dp))
                    androidx.compose.foundation.lazy.LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                        items(data!!.quotes.size) { quoteIndex ->
                            val quote = data!!.quotes[quoteIndex]
                            Row(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = quote.key in homeKeys,
                                    onCheckedChange = { checked ->
                                        homeKeys = when {
                                            checked && homeKeys.size < MAX_HOME_CARDS -> (homeKeys + quote.key).distinct()
                                            checked -> homeKeys
                                            else -> homeKeys - quote.key
                                        }
                                    }
                                )
                                Text(quote.label, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.edit().putString(HOME_KEYS, homeKeys.joinToString(",")).apply()
                    showHomeSettings = false
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { showHomeSettings = false }) { Text("انصراف") } }
        )
    }

    if (showWidgetSettings && data != null) {
        WidgetSettingsDialog(quotes = data!!.quotes, onDismiss = { showWidgetSettings = false }) { keys ->
            MarketWidgetProvider.saveWidgetSelection(context, keys)
            showWidgetSettings = false
        }
    }
}

// Resolve the requested defaults against the live HANSHA feed; never invent prices.
// All quotes returned by HANSHA remain available in both asset pickers.
private fun defaultHomeAssetKeys(quotes: List<Quote>): List<String> {
    val requestedGroups = listOf(
        listOf("usd", "us dollar", "دلار"),
        listOf("usdt", "tether", "تتر"),
        listOf("aed", "dirham", "درهم"),
        listOf("gram", "18k", "18 karat", "gold gram", "گرم", "۱۸ عیار"),
        listOf("emami", "full coin", "bahar azadi", "سکه امامی", "تمام سکه"),
        listOf("nim", "half coin", "نیم سکه"),
        listOf("rob", "quarter coin", "ربع سکه"),
        listOf("try", "turkish lira", "لیر ترکیه"),
        listOf("cny", "yuan", "rmb", "یوان"),
        listOf("ounce", "xau", "ons", "اونس")
    )
    val used = mutableSetOf<String>()
    val chosen = requestedGroups.mapNotNull { aliases ->
        val exact = quotes.firstOrNull { q ->
            val key = q.key.lowercase(Locale.ROOT).replace("_", " ").replace("-", " ")
            aliases.any { alias -> key == alias || q.label.lowercase(Locale.ROOT).contains(alias) }
        }
        val match = exact ?: quotes.firstOrNull { q ->
            val key = q.key.lowercase(Locale.ROOT).replace("_", " ").replace("-", " ")
            aliases.any { alias -> key.contains(alias) || q.label.lowercase(Locale.ROOT).contains(alias) }
        }
        match?.key?.takeIf { used.add(it) }
    }
    // If a requested instrument is absent from the feed, fill with other real feed entries.
    return (chosen + quotes.map { it.key }.filter { used.add(it) }).take(MAX_HOME_CARDS)
}

@Composable
private fun WidgetSettingsDialog(quotes: List<Quote>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val defaults = listOf("usd", "gram", "emami", "usdt")
    var selected by remember {
        mutableStateOf((0..3).map { i -> prefs.getString("widget_slot_" + (i + 1), defaults[i]) ?: defaults[i] })
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب چهار کارت ویجت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEachIndexed { index, key ->
                    Column {
                        Text("کارت ${index + 1}", color = Muted, fontSize = 12.sp)
                        AssetPicker(quotes, key) { newKey ->
                            selected = selected.toMutableList().also { it[index] = newKey }
                        }
                    }
                }
                Text("ظاهر و چیدمان فعلی ویجت بدون تغییر باقی می‌ماند.", color = Muted, fontSize = 11.sp)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(selected) }) { Text("ذخیره و اعمال") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
private fun AssetPicker(quotes: List<Quote>, selectedKey: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val current = quotes.firstOrNull { it.key == selectedKey }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(current?.label ?: selectedKey, color = White, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.heightIn(max = 320.dp)
        ) {
            quotes.forEach { quote ->
                DropdownMenuItem(
                    text = { Text(quote.label) },
                    onClick = { onSelect(quote.key); expanded = false }
                )
            }
        }
    }
}

@Composable
private fun QuoteCard(q: Quote) {
    val changeColor = when { q.pct == null || q.pct == 0.0 -> Muted; q.pct > 0 -> Green; else -> Red }
    Column(
        Modifier.fillMaxWidth()
            .background(CardBg, RoundedCornerShape(16.dp))
            .padding(11.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(q.label, color = White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(q.pct?.let { String.format(Locale.US, "%.2f", it) + "%" } ?: "—", color = changeColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(7.dp))
        Row(
            Modifier.fillMaxWidth().background(Panel, RoundedCornerShape(9.dp)).padding(vertical = 8.dp, horizontal = 5.dp)
        ) {
            PriceColumn("خرید", q.buy, Green, Modifier.weight(1f))
            Box(Modifier.width(1.dp).height(32.dp).background(Stroke))
            PriceColumn("فروش", q.sell, Blue, Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(if (q.fresh) "● زنده" else "● قدیمی/نامعتبر", color = if (q.fresh) Green else Red, fontSize = 9.sp)
            Text("HANSHA", color = Muted, fontSize = 9.sp)
        }
        q.timestamp?.let {
            Text(formatTime(it), color = Muted, fontSize = 9.sp, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.End)
        }
    }
}

@Composable
private fun PriceColumn(label: String, value: Double?, color: Color, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value?.let { num(it) } ?: "—", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("تومان", color = Muted, fontSize = 8.sp)
    }
}

private fun fetchPrices(): PriceData {
    val connection = URL(API_URL).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 15000
    connection.readTimeout = 15000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("Cache-Control", "no-cache")
    try {
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val root = JSONObject(body)
        if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "پاسخ نامعتبر سرور"))
        val items = root.optJSONObject("items") ?: JSONObject()
        val quotes = mutableListOf<Quote>()
        val keys = items.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = items.optJSONObject(key) ?: continue
            val hansha = item.optJSONObject("hansha") ?: continue
            val timestamp = hansha.optString("timestamp").takeIf { it.isNotBlank() && it != "null" }
            val price = hansha.numOrNull("price")
            val labelFields = listOf("label_fa", "name_fa", "title_fa", "fa_name", "label", "name", "title", "symbol")
            var label: String? = null
            for (field in labelFields) {
                val value = item.optString(field)
                if (value.isNotBlank() && value != "null") { label = value; break }
            }
            if (label == null) label = key.replace('_', ' ').replace('-', ' ').uppercase(Locale.ROOT)
            val buy = hansha.numOrNull("buy")
            val sell = hansha.numOrNull("sell") ?: price
            quotes.add(Quote(key, label, price, buy, sell, hansha.numOrNull("pct"), timestamp, price != null && isFresh(timestamp)))
        }
        return PriceData(quotes.sortedBy { it.label }, root.optString("updated_at").takeIf { it.isNotBlank() && it != "null" })
    } finally {
        connection.disconnect()
    }
}

private fun JSONObject.numOrNull(key: String): Double? =
    if (isNull(key)) null else optDouble(key).takeIf { it.isFinite() && it > 0.0 }

private fun num(value: Double): String =
    DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(Locale("fa", "IR"))).format(value)

private fun isFresh(timestamp: String?): Boolean {
    val time = parseTimestamp(timestamp) ?: return false
    val age = (System.currentTimeMillis() - time) / 1000L
    return age in 0..MAX_PRICE_AGE_SECONDS
}

private fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
    for (pattern in patterns) try {
        val parser = SimpleDateFormat(pattern, Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
            isLenient = false
        }
        val parsed = parser.parse(value)
        if (parsed != null) return parsed.time
    } catch (_: Exception) { }
    return null
}

private fun formatTime(value: String): String {
    val date = parseTimestamp(value) ?: return "نامشخص"
    return SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date(date))
}
