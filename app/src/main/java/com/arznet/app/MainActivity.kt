package com.arznet.prices

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
private val Bg = Color(0xFF101216)
private val CardBg = Color(0xFF1B1F26)
private val Blue = Color(0xFF79A9FF)
private val White = Color(0xFFF3F5F7)
private val Muted = Color(0xFF9AA4B2)
private val Green = Color(0xFF43D19E)
private val Red = Color(0xFFFF7272)

data class Quote(val key: String, val label: String, val price: Double?, val buy: Double?, val pct: Double?, val timestamp: String?, val fresh: Boolean)
data class PriceData(val quotes: List<Quote>, val updatedAt: String?)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 18, 22)
        window.navigationBarColor = android.graphics.Color.rgb(16, 18, 22)
        setContent { MaterialTheme(colorScheme = darkColorScheme(primary = Blue, background = Bg, surface = CardBg, onBackground = White, onSurface = White)) { PricesScreen() } }
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
    var homeKeys by remember { mutableStateOf(prefs.getString(HOME_KEYS, "")!!.split(",").filter { it.isNotBlank() }) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = data == null
        try {
            val received = withContext(Dispatchers.IO) { fetchPrices() }
            data = received
            if (homeKeys.isEmpty()) {
                homeKeys = received.quotes.map { it.key }
                prefs.edit().putString(HOME_KEYS, homeKeys.joinToString(",")).apply()
            } else {
                homeKeys = homeKeys.filter { key -> received.quotes.any { it.key == key } }
            }
            error = null
        } catch (e: Exception) {
            error = e.message ?: "دریافت اطلاعات ناموفق بود"
        } finally { loading = false }
    }

    LaunchedEffect(Unit) {
        refresh()
        while (isActive) { delay(60_000); refresh() }
    }

    Column(Modifier.fillMaxSize().background(Bg).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("ARZ-NET", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text("قیمت لحظه‌ای بازار", color = White, fontSize = 25.sp, fontWeight = FontWeight.Bold)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = { scope.launch { refresh() } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28364C))) {
                    Text(if (loading) "..." else "به‌روزرسانی")
                }
                Box {
                    IconButton(onClick = { menuExpanded = true }) { Text("⋮", color = White, fontSize = 28.sp, fontWeight = FontWeight.Bold) }
                    DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
                        DropdownMenuItem(text = { Text("انتخاب کارت‌های صفحه اصلی") }, onClick = { menuExpanded = false; showHomeSettings = true })
                        DropdownMenuItem(text = { Text("تنظیمات ویجت") }, onClick = { menuExpanded = false; showWidgetSettings = true })
                    }
                }
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("منبع قیمت: HANSHA • " + (data?.quotes?.size ?: 0) + " دارایی", color = Muted, fontSize = 12.sp)
        Spacer(Modifier.height(14.dp))
        if (loading && data == null) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Blue) }
        } else if (data == null) {
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("دریافت قیمت‌ها انجام نشد", color = Red)
                Text(error ?: "اتصال اینترنت را بررسی کنید", color = Muted)
                TextButton(onClick = { scope.launch { refresh() } }) { Text("تلاش دوباره") }
            }
        } else {
            val visibleQuotes = data!!.quotes.filter { it.key in homeKeys }
            if (visibleQuotes.isEmpty()) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("هیچ کارتی انتخاب نشده است. از منوی سه‌نقطه کارت‌ها را انتخاب کنید.", color = Muted, textAlign = TextAlign.Center)
                }
            } else {
                LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(visibleQuotes, key = { it.key }) { QuoteCard(it) }
                }
            }
        }
        data?.updatedAt?.let { Text("به‌روزرسانی سرور: " + formatTime(it), Modifier.fillMaxWidth(), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center) }
        error?.let { if (data != null) Text("خطای تازه‌سازی: " + it, color = Red, fontSize = 11.sp) }
    }

    if (showHomeSettings && data != null) {
        AlertDialog(
            onDismissRequest = { showHomeSettings = false },
            title = { Text("کارت‌های صفحه اصلی") },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 440.dp)) {
                    items(data!!.quotes, key = { it.key }) { quote ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = quote.key in homeKeys, onCheckedChange = { checked ->
                                homeKeys = if (checked) (homeKeys + quote.key).distinct() else homeKeys - quote.key
                            })
                            Text(quote.label, color = White)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { prefs.edit().putString(HOME_KEYS, homeKeys.joinToString(",")).apply(); showHomeSettings = false }) { Text("ذخیره") } },
            dismissButton = { TextButton(onClick = { showHomeSettings = false }) { Text("انصراف") } }
        )
    }

    if (showWidgetSettings && data != null) {
        WidgetSettingsDialog(quotes = data!!.quotes, onDismiss = { showWidgetSettings = false }) { keys ->
            MarketWidgetProvider.saveWidgetSelection(context, keys)
            MarketWidgetProvider.refreshAll(context)
            showWidgetSettings = false
        }
    }
}

@Composable
private fun WidgetSettingsDialog(quotes: List<Quote>, onDismiss: () -> Unit, onSave: (List<String>) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val defaults = listOf("usd", "gram", "emami", "usdt")
    var selected by remember { mutableStateOf((0..3).map { i -> prefs.getString("widget_slot_" + (i + 1), defaults[i]) ?: defaults[i] }) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("انتخاب چهار کارت ویجت") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                selected.forEachIndexed { index, key ->
                    Column {
                        Text("کارت " + (index + 1), color = Muted, fontSize = 12.sp)
                        AssetPicker(quotes, key) { newKey -> selected = selected.toMutableList().also { it[index] = newKey } }
                    }
                }
                Text("ظاهر و چیدمان ویجت بدون تغییر باقی می‌ماند.", color = Muted, fontSize = 11.sp)
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
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(current?.label ?: selectedKey, color = White) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
            quotes.forEach { quote -> DropdownMenuItem(text = { Text(quote.label) }, onClick = { onSelect(quote.key); expanded = false }) }
        }
    }
}

@Composable
private fun QuoteCard(q: Quote) {
    val changeColor = when { q.pct == null || q.pct == 0.0 -> Muted; q.pct > 0 -> Green; else -> Red }
    Column(Modifier.fillMaxWidth().background(CardBg, RoundedCornerShape(18.dp)).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(q.label, color = White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("HANSHA", color = Blue, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        Text(q.price?.let { num(it) + " تومان" } ?: "قیمت در دسترس نیست", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Text(when {
            q.price == null -> "قیمت دریافت نشد"
            q.fresh -> "زنده • زمان قیمت کمتر از ۶۰ ثانیه"
            q.timestamp == null -> "قدیمی/نامعتبر • زمان قیمت موجود نیست"
            else -> "قدیمی • برای تصمیم مالی به قیمت زنده اتکا نکنید"
        }, color = if (q.fresh) Green else Red, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(q.buy?.let { "خرید: " + num(it) + " تومان" } ?: " ", color = Muted, fontSize = 12.sp)
            Text(q.pct?.let { String.format(Locale.US, "%.2f", it) + "٪" } ?: "تغییر: —", color = changeColor, fontSize = 13.sp)
        }
        q.timestamp?.let { Spacer(Modifier.height(6.dp)); Text("زمان قیمت: " + formatTime(it), color = Muted, fontSize = 10.sp) }
    }
}

private fun fetchPrices(): PriceData {
    val c = URL(API_URL).openConnection() as HttpURLConnection
    c.requestMethod = "GET"; c.connectTimeout = 15000; c.readTimeout = 15000
    c.setRequestProperty("Accept", "application/json"); c.setRequestProperty("Cache-Control", "no-cache")
    try {
        val code = c.responseCode
        val stream = if (code in 200..299) c.inputStream else c.errorStream
        val body = stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        if (code !in 200..299) throw IllegalStateException("HTTP " + code)
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
            quotes.add(Quote(key, label, price, hansha.numOrNull("buy"), hansha.numOrNull("pct"), timestamp, price != null && isFresh(timestamp)))
        }
        return PriceData(quotes.sortedBy { it.label }, root.optString("updated_at").takeIf { it.isNotBlank() && it != "null" })
    } finally { c.disconnect() }
}

private fun JSONObject.numOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { it.isFinite() && it > 0.0 }
private fun num(v: Double) = DecimalFormat("#,##0.##", java.text.DecimalFormatSymbols(Locale("fa", "IR"))).format(v)
private fun isFresh(timestamp: String?): Boolean {
    val time = parseTimestamp(timestamp) ?: return false
    val age = (System.currentTimeMillis() - time) / 1000L
    return age in 0..MAX_PRICE_AGE_SECONDS
}
private fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val patterns = listOf("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
    for (pattern in patterns) try {
        val parser = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC"); isLenient = false }
        val parsed = parser.parse(value)
        if (parsed != null) return parsed.time
    } catch (_: Exception) { }
    return null
}
private fun formatTime(s: String): String {
    val d = parseTimestamp(s) ?: return "نامشخص"
    return SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date(d))
}
