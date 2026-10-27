package com.arznet.prices

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
private val Bg = Color(0xFF101216)
private val CardBg = Color(0xFF1B1F26)
private val Blue = Color(0xFF79A9FF)
private val White = Color(0xFFF3F5F7)
private val Muted = Color(0xFF9AA4B2)
private val Green = Color(0xFF43D19E)
private val Red = Color(0xFFFF7272)

data class Quote(val key: String, val label: String, val price: Double?, val buy: Double?, val pct: Double?, val timestamp: String?)
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
    var data by remember { mutableStateOf<PriceData?>(null) }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = data == null
        try {
            data = withContext(Dispatchers.IO) { fetchPrices() }
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
            Button(onClick = { scope.launch { refresh() } }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF28364C))) {
                Text(if (loading) "..." else "به‌روزرسانی")
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("منبع قیمت: HANSHA", color = Muted, fontSize = 12.sp)
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
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(data!!.quotes) { QuoteCard(it) }
                item { Text("مقایسه TGJU فعلاً فعال نیست؛ پاسخ سرور برای TGJU مقدار null برمی‌گرداند.", color = Muted, fontSize = 11.sp) }
            }
        }
        data?.updatedAt?.let { Text("به‌روزرسانی سرور: ${formatTime(it)}", Modifier.fillMaxWidth(), color = Muted, fontSize = 11.sp, textAlign = TextAlign.Center) }
        error?.let { if (data != null) Text("خطای تازه‌سازی: $it", color = Red, fontSize = 11.sp) }
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
        Text(q.price?.let { "${num(it)} تومان" } ?: "قیمت در دسترس نیست", color = White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(q.buy?.let { "خرید: ${num(it)} تومان" } ?: " ", color = Muted, fontSize = 12.sp)
            Text(q.pct?.let { "${if (it > 0) "+" else ""}${String.format(Locale.US, "%.2f", it)}٪" } ?: "تغییر: —", color = changeColor, fontSize = 13.sp)
        }
        q.timestamp?.let { Spacer(Modifier.height(6.dp)); Text("زمان قیمت: ${formatTime(it)}", color = Muted, fontSize = 10.sp) }
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
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val root = JSONObject(body)
        if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "پاسخ نامعتبر سرور"))
        val items = root.optJSONObject("items") ?: JSONObject()
        val defs = listOf("usd" to "دلار آزاد", "emami" to "سکه امامی", "gram" to "طلای ۱۸ عیار (گرم)", "usdt" to "تتر")
        val quotes = defs.map { (key, label) ->
            val h = items.optJSONObject(key)?.optJSONObject("hansha")
            Quote(key, label, h?.numOrNull("price"), h?.numOrNull("buy"), h?.numOrNull("pct"),
                h?.optString("timestamp")?.takeIf { it.isNotBlank() && it != "null" })
        }
        return PriceData(quotes, root.optString("updated_at").takeIf { it.isNotBlank() && it != "null" })
    } finally { c.disconnect() }
}

private fun JSONObject.numOrNull(k: String): Double? = if (isNull(k)) null else optDouble(k).takeIf { it.isFinite() }
private fun num(v: Double) = DecimalFormat("#,##0.##").format(v)
private fun formatTime(s: String): String = try {
    val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
    val d = parser.parse(s) ?: return s
    SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(d)
} catch (_: Exception) { s }
