package com.arznet.prices

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OutOfQuotaPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

private const val WIDGET_API_URL = "https://hansha.online/latest"
private const val ACTION_REFRESH = "com.arznet.prices.WIDGET_REFRESH"
private const val ACTION_CONFIG_CHANGED = "com.arznet.prices.WIDGET_CONFIG_CHANGED"
private const val MAX_PRICE_AGE_SECONDS = 60L

private data class WidgetQuote(
    val key: String,
    val label: String,
    val price: Double?,
    val timestamp: String?,
    val fresh: Boolean
)

private data class WidgetPrices(
    val quotes: List<WidgetQuote>,
    val serverUpdatedAt: String?,
    val fromCache: Boolean = false
)

private data class WidgetCatalogAsset(val key: String, val label: String, val aliases: List<String>)
private val widgetCatalog = listOf(
    WidgetCatalogAsset("usd", "دلار آمریکا", listOf("usd", "dollar", "دلار آزاد", "دلار آمریکا")),
    WidgetCatalogAsset("usdt", "تتر", listOf("usdt", "tether", "تتر")),
    WidgetCatalogAsset("aed", "درهم امارات", listOf("aed", "dirham", "درهم")),
    WidgetCatalogAsset("gold_18k_gram", "طلای ۱۸ عیار (گرم)", listOf("gold18", "18k", "18 karat", "18 عیار", "طلای ۱۸")),
    WidgetCatalogAsset("coin_emami", "سکه تمام امامی", listOf("emami", "سکه امامی", "تمام امامی")),
    WidgetCatalogAsset("coin_bahar_old", "سکه تمام بهار آزادی طرح قدیم", listOf("bahar", "طرح قدیم", "بهار آزادی")),
    WidgetCatalogAsset("coin_half", "نیم‌سکه", listOf("nim", "half coin", "نیم سکه", "نیم‌سکه")),
    WidgetCatalogAsset("coin_quarter", "ربع‌سکه", listOf("rob", "quarter coin", "ربع سکه", "ربع‌سکه")),
    WidgetCatalogAsset("coin_gram", "سکه یک‌گرمی", listOf("gerami", "gram coin", "سکه یک گرمی", "سکه یک‌گرمی")),
    WidgetCatalogAsset("try", "لیر ترکیه", listOf("try", "turkish lira", "لیر ترکیه")),
    WidgetCatalogAsset("cny", "یوان چین", listOf("cny", "yuan", "rmb", "یوان")),
    WidgetCatalogAsset("xau", "اونس جهانی طلا", listOf("xau", "gold ounce", "اونس طلا", "اونس جهانی")),
    WidgetCatalogAsset("xag", "اونس جهانی نقره", listOf("xag", "silver ounce", "اونس نقره")),
    WidgetCatalogAsset("xpt", "اونس جهانی پلاتین", listOf("xpt", "platinum", "اونس پلاتین")),
    WidgetCatalogAsset("xpd", "اونس جهانی پالادیوم", listOf("xpd", "palladium", "اونس پالادیوم"))
)

private fun normalizeWidgetAsset(value: String): String =
    value.lowercase(Locale.ROOT).replace("_", "").replace("-", "").replace(" ", "").replace("‌", "")

private fun widgetMatchesCatalog(key: String, label: String, asset: WidgetCatalogAsset): Boolean {
    val k = normalizeWidgetAsset(key)
    val n = normalizeWidgetAsset(label)
    return asset.aliases.any { alias ->
        val a = normalizeWidgetAsset(alias)
        k == a || n == a || (a.length >= 4 && (k.contains(a) || n.contains(a)))
    }
}

private fun addWidgetPlaceholders(quotes: List<WidgetQuote>): List<WidgetQuote> {
    val result = quotes.toMutableList()
    widgetCatalog.forEach { asset ->
        if (result.none { widgetMatchesCatalog(it.key, it.label, asset) }) {
            result.add(WidgetQuote(asset.key, asset.label, null, null, false))
        }
    }
    return result
}

private fun widgetAssetIcon(key: String, label: String): String {
    val value = normalizeWidgetAsset("$key $label")
    return when {
        value.contains("usdt") || value.contains("tether") || value.contains("تتر") -> "₮"
        value.contains("aed") || value.contains("dirham") || value.contains("درهم") -> "🇦🇪"
        normalizeWidgetAsset(key) == "try" || normalizeWidgetAsset(key).startsWith("try") || value.contains("turkish") || value.contains("لیرترکیه") -> "🇹🇷"
        value.contains("cny") || value.contains("yuan") || value.contains("یوان") -> "🇨🇳"
        value.contains("usd") || value.contains("dollar") || value.contains("دلار") -> "🇺🇸"
        value.contains("xag") || value.contains("silver") || value.contains("نقره") -> "⚪"
        value.contains("xpt") || value.contains("platinum") || value.contains("پلاتین") -> "⚙️"
        value.contains("xpd") || value.contains("palladium") || value.contains("پالادیوم") -> "💠"
        value.contains("xau") || value.contains("ounce") || value.contains("اونس") -> "🌐"
        value.contains("coin") || value.contains("سکه") || value.contains("emami") || value.contains("bahar") -> "🪙"
        value.contains("gold") || value.contains("gram") || value.contains("طلا") -> "🟡"
        else -> "💱"
    }
}

class MarketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleWidgetWork(context)
        ids.forEach { id -> showLoading(context, manager, id) }
        refreshInBackground(context)
    }

    override fun onEnabled(context: Context) {
        scheduleWidgetWork(context)
    }

    override fun onDisabled(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork("arznet-market-widget-refresh-now")
        WorkManager.getInstance(context).cancelUniqueWork("arznet-market-widget-refresh")
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == ACTION_CONFIG_CHANGED) {
            refreshInBackground(context, expedited = true)
            return
        }
        if (intent.action != ACTION_REFRESH) {
            super.onReceive(context, intent)
            return
        }

        // Update the status immediately, then let expedited WorkManager work perform network I/O.
        val manager = AppWidgetManager.getInstance(context)
        val component = ComponentName(context, MarketWidgetProvider::class.java)
        manager.getAppWidgetIds(component).forEach { id -> showLoading(context, manager, id) }
        refreshInBackground(context, expedited = true)
    }

    companion object {
        fun saveWidgetSelection(context: Context, keys: List<String>) {
            val prefs = context.getSharedPreferences("arznet_display_preferences", Context.MODE_PRIVATE)
            val defaults = listOf("usd", "gram", "emami", "usdt")
            val editor = prefs.edit()
            for (index in 0 until 4) editor.putString("widget_slot_" + (index + 1), keys.getOrNull(index) ?: defaults[index])
            editor.apply()
            context.sendBroadcast(Intent(context, MarketWidgetProvider::class.java).setAction(ACTION_CONFIG_CHANGED))
        }

        fun refreshAll(context: Context): Boolean {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MarketWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return true

            var errorMessage: String? = null
            var liveFetchSucceeded = false
            val prices = try {
                val result = fetchWidgetPrices(context)
                liveFetchSucceeded = !result.fromCache
                result
            } catch (e: Exception) {
                android.util.Log.e("ARZNETWidget", "Price fetch failed", e)
                val detail = e.message?.takeIf { it.isNotBlank() } ?: "No details"
                errorMessage = "${e.javaClass.simpleName}: $detail".take(90)
                null
            }
            ids.forEach { id -> render(context, manager, id, prices, errorMessage) }
            return liveFetchSucceeded
        }

        private fun refreshInBackground(context: Context, expedited: Boolean = false) {
            val builder = OneTimeWorkRequestBuilder<MarketWidgetWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
            if (expedited) {
                builder.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            }
            val workName = if (expedited) {
                "arznet-market-widget-refresh-now"
            } else {
                "arznet-market-widget-refresh"
            }
            WorkManager.getInstance(context).enqueueUniqueWork(
                workName,
                androidx.work.ExistingWorkPolicy.KEEP,
                builder.build()
            )
        }

        private fun showLoading(context: Context, manager: AppWidgetManager, id: Int) {
            // Partial update preserves the last displayed prices while refreshing.
            val views = RemoteViews(context.packageName, R.layout.market_widget)
            // Keep the whole widget tappable even during the initial loading state.
            // The refresh button remains separate; tapping the widget body opens MainActivity.
            setRefreshIntent(context, views)
            views.setTextViewText(R.id.widget_status, "در حال به‌روزرسانی قیمت‌ها…")
            manager.partiallyUpdateAppWidget(id, views)
        }

        private fun render(
            context: Context,
            manager: AppWidgetManager,
            id: Int,
            prices: WidgetPrices?,
            errorMessage: String? = null
        ) {
            val views = RemoteViews(context.packageName, R.layout.market_widget)
            setRefreshIntent(context, views)

            val slots = listOf(
                arrayOf(R.id.widget_usd_title, R.id.widget_usd_code, R.id.widget_usd_price),
                arrayOf(R.id.widget_gold_title, R.id.widget_gold_code, R.id.widget_gold_price),
                arrayOf(R.id.widget_coin_title, R.id.widget_coin_code, R.id.widget_coin_price),
                arrayOf(R.id.widget_usdt_title, R.id.widget_usdt_code, R.id.widget_usdt_price)
            )
            val iconIds = listOf(
                R.id.widget_usd_icon, R.id.widget_gold_icon,
                R.id.widget_coin_icon, R.id.widget_usdt_icon
            )
            val defaults = listOf("usd", "gram", "emami", "usdt")
            val prefs = context.getSharedPreferences("arznet_display_preferences", Context.MODE_PRIVATE)
            val selectedKeys = (0 until 4).map { index ->
                prefs.getString("widget_slot_" + (index + 1), defaults[index]) ?: defaults[index]
            }
            val byKey = prices?.quotes?.associateBy { it.key } ?: emptyMap()
            slots.forEachIndexed { index, slot ->
                val quote = byKey[selectedKeys[index]]
                views.setTextViewText(slot[0], quote?.label ?: selectedKeys[index].uppercase(Locale.ROOT))
                views.setTextViewText(slot[1], selectedKeys[index].uppercase(Locale.ROOT))
                views.setTextViewText(iconIds[index], widgetAssetIcon(quote?.key ?: selectedKeys[index], quote?.label ?: selectedKeys[index]))
                setPrice(views, slot[2], quote?.price, prices != null && !prices.fromCache && quote?.fresh == true)
            }

            if (prices == null) {
                views.setTextViewText(R.id.widget_status, "خطا: " + (errorMessage ?: "دریافت اطلاعات ناموفق بود"))
                manager.updateAppWidget(id, views)
                return
            }

            val hasStale = prices.quotes.any { it.price != null && (!it.fresh || prices.fromCache) }
            val hasFresh = prices.quotes.any { it.price != null && it.fresh && !prices.fromCache }
            val serverTime = prices.serverUpdatedAt?.let { formatTime(it) }
            val status = when {
                prices.fromCache -> "اتصال برقرار نشد • قیمت ذخیره‌شده (قدیمی)"
                hasStale && hasFresh -> "هشدار: بعضی قیمت‌ها قدیمی‌اند"
                hasStale -> "هشدار: قیمت قدیمی است؛ قیمت زنده موجود نیست"
                hasFresh && serverTime != null -> "قیمت زنده • پاسخ سرور $serverTime"
                hasFresh -> "قیمت زنده • عمر قیمت حداکثر ۶۰ ثانیه است"
                else -> "قیمت زنده در دسترس نیست؛ زمان معتبر ≤ ۶۰ ثانیه لازم است"
            }
            views.setTextViewText(R.id.widget_status, status)
            manager.updateAppWidget(id, views)
        }

        private fun setPrice(views: RemoteViews, viewId: Int, price: Double?, fresh: Boolean) {
            val text = when {
                price == null -> "قیمت دریافت نشد"
                fresh -> "${formatNumber(price)} تومان"
                else -> "${formatNumber(price)} تومان (قدیمی)"
            }
            views.setTextViewText(viewId, text)
        }

        private fun setRefreshIntent(context: Context, views: RemoteViews) {
            // Refresh button: send a broadcast to refresh prices without opening the app.
            val refreshIntent = Intent(context, MarketWidgetProvider::class.java).apply {
                action = ACTION_REFRESH
            }
            val refreshPendingIntent = PendingIntent.getBroadcast(
                context,
                7301,
                refreshIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, refreshPendingIntent)

            // Widget body: open the app directly without triggering a refresh action.
            val launchIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
            if (launchIntent != null) {
                launchIntent.addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                )
                val openAppPendingIntent = PendingIntent.getActivity(
                    context,
                    7302,
                    launchIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                views.setOnClickPendingIntent(R.id.widget_root, openAppPendingIntent)
            }
        }

        private fun scheduleWidgetWork(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<MarketWidgetWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "arznet-market-widget-refresh",
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
        }
    }
}

class MarketWidgetWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return try {
            if (MarketWidgetProvider.refreshAll(applicationContext)) Result.success() else Result.retry()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun fetchWidgetPrices(context: Context): WidgetPrices {
    val prefs = context.getSharedPreferences("arznet_widget_cache", Context.MODE_PRIVATE)
    var lastError: Exception? = null
    var body: String? = null

    // Retry transient DNS/network errors instead of treating the first failure as final.
    repeat(2) { attempt ->
        try {
            val connection = (URL(WIDGET_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 4000
                readTimeout = 4000
                useCaches = false
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Cache-Control", "no-cache, no-store")
                setRequestProperty("Pragma", "no-cache")
                setRequestProperty("User-Agent", "ARZ-NET-Android/1.2")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) throw IllegalStateException("HTTP $code")
                val received = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                val trimmed = received.trim()
                if (trimmed.startsWith("[")) {
                    org.json.JSONArray(trimmed)
                } else {
                    val check = JSONObject(trimmed)
                    if (!check.optBoolean("ok")) throw IllegalStateException(check.optString("error", "پاسخ نامعتبر"))
                }
                body = received
                prefs.edit().putString("last_json", received).apply()
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("ARZNETWidget", "Fetch attempt ${attempt + 1}/2 failed", e)
            if (attempt < 1) try { Thread.sleep(350L) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (body != null) return parseWidgetPrices(body!!, fromCache = false)
    }

    // If the network is down, show the last received prices clearly marked as old.
    val cached = prefs.getString("last_json", null)
    if (!cached.isNullOrBlank()) return parseWidgetPrices(cached, fromCache = true)
    throw lastError ?: IllegalStateException("دریافت قیمت ناموفق بود")
}

private fun parseWidgetPrices(body: String, fromCache: Boolean): WidgetPrices {
    val trimmed = body.trim()
    val quotes = mutableListOf<WidgetQuote>()
    if (trimmed.startsWith("[")) {
        // Native HANSHA /latest array format.
        val rows = org.json.JSONArray(trimmed)
        for (index in 0 until rows.length()) {
            val item = rows.optJSONObject(index) ?: continue
            val key = item.optString("ab").trim().lowercase(Locale.ROOT)
            if (key.isBlank() || key == "null") continue
            val label = item.optString("fa").takeIf { it.isNotBlank() && it != "null" }
                ?: item.optString("en").takeIf { it.isNotBlank() && it != "null" }
                ?: key.uppercase(Locale.ROOT)
            val latest = item.optJSONArray("ps")?.optJSONObject(0)
            val buy = latest?.numberOrNull("bp")
            val sell = latest?.numberOrNull("sp")
            val price = if (buy != null && sell != null) (buy + sell) / 2.0 else buy ?: sell
            val timestamp = latest?.optString("ts")?.takeIf { it.isNotBlank() && it != "null" }
            quotes.add(WidgetQuote(key, label, price, timestamp, price != null && isFresh(timestamp)))
        }
    } else {
        // Backward compatibility with the former proxy envelope.
        val root = JSONObject(trimmed)
        if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "پاسخ نامعتبر"))
        val items = root.optJSONObject("items") ?: JSONObject()
        val keys = items.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val item = items.optJSONObject(key) ?: continue
            val hansha = item.optJSONObject("hansha") ?: continue
            val timestamp = hansha.optString("timestamp").takeIf { it.isNotBlank() && it != "null" }
            val price = hansha.numberOrNull("price")
            val fields = listOf("label_fa", "name_fa", "title_fa", "fa_name", "label", "name", "title", "symbol")
            var label: String? = null
            for (field in fields) {
                val value = item.optString(field)
                if (value.isNotBlank() && value != "null") { label = value; break }
            }
            if (label == null) label = key.replace('_', ' ').replace('-', ' ').uppercase(Locale.ROOT)
            quotes.add(WidgetQuote(key, label, price, timestamp, price != null && isFresh(timestamp)))
        }
    }
    if (quotes.isEmpty()) throw IllegalStateException("در پاسخ HANSHA هیچ دارایی قابل‌خواندن پیدا نشد")
    return WidgetPrices(addWidgetPlaceholders(quotes), null, fromCache)
}

private fun JSONObject.numberOrNull(key: String): Double? {
    if (isNull(key)) return null
    return optDouble(key).takeIf { it.isFinite() && it > 0.0 }
}

private fun isFresh(timestamp: String?): Boolean {
    val time = parseTimestamp(timestamp) ?: return false
    val age = (System.currentTimeMillis() - time) / 1000L
    return age in 0..MAX_PRICE_AGE_SECONDS
}

private fun parseTimestamp(value: String?): Long? {
    if (value.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
        "yyyy-MM-dd'T'HH:mm:ss'Z'",
        "yyyy-MM-dd'T'HH:mm:ssXXX"
    )
    for (pattern in patterns) {
        try {
            val parser = SimpleDateFormat(pattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            return parser.parse(value)?.time
        } catch (_: Exception) {
            // Try the next supported ISO-8601 timestamp pattern.
        }
    }
    return null
}

private fun formatNumber(value: Double): String {
    val symbols = DecimalFormatSymbols(Locale("fa", "IR"))
    return DecimalFormat("#,##0.##", symbols).format(value)
}

private fun formatTime(value: String): String {
    val time = parseTimestamp(value) ?: return "نامشخص"
    return SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date(time))
}
