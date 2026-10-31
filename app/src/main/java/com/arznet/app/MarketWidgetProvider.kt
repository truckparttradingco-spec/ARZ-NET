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

private const val WIDGET_API_URL = "https://truckparttrading.com/price-list/chand/kwgt-proxy.php"
private const val ACTION_REFRESH = "com.arznet.prices.WIDGET_REFRESH"
private const val MAX_PRICE_AGE_SECONDS = 60L

private data class WidgetQuote(
    val key: String,
    val price: Double?,
    val timestamp: String?,
    val fresh: Boolean
)

private data class WidgetPrices(
    val quotes: List<WidgetQuote>,
    val serverUpdatedAt: String?,
    val fromCache: Boolean = false
)

class MarketWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        scheduleWidgetWork(context)
        ids.forEach { id -> showLoading(context, manager, id) }
        refreshInBackground(context)
    }

    override fun onEnabled(context: Context) {
        scheduleWidgetWork(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == ACTION_REFRESH) refreshInBackground(context)
    }

    companion object {
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

        private fun refreshInBackground(context: Context) {
            val request = OneTimeWorkRequestBuilder<MarketWidgetWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                )
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }

        private fun showLoading(context: Context, manager: AppWidgetManager, id: Int) {
            val views = RemoteViews(context.packageName, R.layout.market_widget)
            views.setTextViewText(R.id.widget_status, "در حال دریافت قیمت‌ها…")
            setPrice(views, R.id.widget_usd_price, null, false)
            setPrice(views, R.id.widget_gold_price, null, false)
            setPrice(views, R.id.widget_coin_price, null, false)
            setPrice(views, R.id.widget_usdt_price, null, false)
            setRefreshIntent(context, views)
            manager.updateAppWidget(id, views)
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

            if (prices == null) {
                views.setTextViewText(
                    R.id.widget_status,
                    "خطا: ${errorMessage ?: "دریافت اطلاعات ناموفق بود"}"
                )
                listOf(
                    R.id.widget_usd_price,
                    R.id.widget_gold_price,
                    R.id.widget_coin_price,
                    R.id.widget_usdt_price
                ).forEach { views.setTextViewText(it, "—") }
                manager.updateAppWidget(id, views)
                return
            }

            val byKey = prices.quotes.associateBy { it.key }
            setPrice(views, R.id.widget_usd_price, byKey["usd"]?.price, !prices.fromCache && byKey["usd"]?.fresh == true)
            setPrice(views, R.id.widget_gold_price, byKey["gram"]?.price, !prices.fromCache && byKey["gram"]?.fresh == true)
            setPrice(views, R.id.widget_coin_price, byKey["emami"]?.price, !prices.fromCache && byKey["emami"]?.fresh == true)
            setPrice(views, R.id.widget_usdt_price, byKey["usdt"]?.price, !prices.fromCache && byKey["usdt"]?.fresh == true)

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
    repeat(3) { attempt ->
        try {
            val connection = (URL(WIDGET_API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 12000
                readTimeout = 12000
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
                val check = JSONObject(received)
                if (!check.optBoolean("ok")) throw IllegalStateException(check.optString("error", "پاسخ نامعتبر"))
                body = received
                prefs.edit().putString("last_json", received).apply()
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            lastError = e
            android.util.Log.w("ARZNETWidget", "Fetch attempt ${attempt + 1}/3 failed", e)
            if (attempt < 2) try { Thread.sleep(1200L * (attempt + 1)) } catch (_: InterruptedException) { Thread.currentThread().interrupt() }
        }
        if (body != null) return parseWidgetPrices(body!!, fromCache = false)
    }

    // If the network is down, show the last received prices clearly marked as old.
    val cached = prefs.getString("last_json", null)
    if (!cached.isNullOrBlank()) return parseWidgetPrices(cached, fromCache = true)
    throw lastError ?: IllegalStateException("دریافت قیمت ناموفق بود")
}

private fun parseWidgetPrices(body: String, fromCache: Boolean): WidgetPrices {
    val root = JSONObject(body)
    if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "پاسخ نامعتبر"))
    val items = root.optJSONObject("items") ?: JSONObject()
    val keys = listOf("usd", "gram", "emami", "usdt")
    val quotes = keys.map { key ->
        val hansha = items.optJSONObject(key)?.optJSONObject("hansha")
        val timestamp = hansha?.optString("timestamp")?.takeIf { it.isNotBlank() && it != "null" }
        val price = hansha?.numberOrNull("price")
        WidgetQuote(key, price, timestamp, price != null && isFresh(timestamp))
    }
    return WidgetPrices(
        quotes = quotes,
        serverUpdatedAt = root.optString("updated_at").takeIf { it.isNotBlank() && it != "null" },
        fromCache = fromCache
    )
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
