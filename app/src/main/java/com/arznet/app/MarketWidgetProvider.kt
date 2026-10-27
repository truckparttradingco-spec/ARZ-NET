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
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.DecimalFormat
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
    val label: String,
    val price: Double?,
    val buy: Double?,
    val timestamp: String?,
    val fresh: Boolean
)

private data class WidgetPrices(val quotes: List<WidgetQuote>, val serverUpdatedAt: String?)

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
        if (intent.action == ACTION_REFRESH) {
            refreshInBackground(context)
        }
    }

    companion object {
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val component = ComponentName(context, MarketWidgetProvider::class.java)
            val ids = manager.getAppWidgetIds(component)
            if (ids.isEmpty()) return
            val prices = try {
                fetchWidgetPrices()
            } catch (_: Exception) {
                null
            }
            ids.forEach { id -> render(context, manager, id, prices) }
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
            views.setTextViewText(R.id.widget_status, "در حال بررسی اعتبار قیمت‌ها…")
            setRefreshIntent(context, views)
            manager.updateAppWidget(id, views)
        }

        private fun render(context: Context, manager: AppWidgetManager, id: Int, prices: WidgetPrices?) {
            val views = RemoteViews(context.packageName, R.layout.market_widget)
            setRefreshIntent(context, views)
            if (prices == null) {
                views.setTextViewText(R.id.widget_status, "اتصال ناموفق؛ برای تلاش دوباره بزنید ↻")
                listOf(R.id.widget_usd, R.id.widget_gold, R.id.widget_coin, R.id.widget_usdt).forEach {
                    views.setTextViewText(it, "قیمت معتبر در دسترس نیست")
                }
                manager.updateAppWidget(id, views)
                return
            }

            val quoteByKey = prices.quotes.associateBy { it.key }
            setQuoteLine(views, R.id.widget_usd, quoteByKey["usd"], "دلار آزاد")
            setQuoteLine(views, R.id.widget_gold, quoteByKey["gram"], "طلای ۱۸ عیار")
            setQuoteLine(views, R.id.widget_coin, quoteByKey["emami"], "سکه امامی")
            setQuoteLine(views, R.id.widget_usdt, quoteByKey["usdt"], "تتر")

            val hasStale = prices.quotes.any { it.price != null && !it.fresh }
            val serverTime = prices.serverUpdatedAt?.let { formatTime(it) }
            val status = when {
                hasStale -> "هشدار: قیمت قدیمی/نامعتبر پنهان شده"
                serverTime != null -> "داده معتبر • پاسخ سرور $serverTime"
                else -> "قیمت‌ها بررسی شدند؛ زمان سرور نامشخص"
            }
            views.setTextViewText(R.id.widget_status, status)
            manager.updateAppWidget(id, views)
        }

        private fun setQuoteLine(views: RemoteViews, viewId: Int, q: WidgetQuote?, label: String) {
            val value = when {
                q == null || q.price == null -> "قیمت معتبر نیست"
                !q.fresh -> "داده کهنه؛ نمایش داده نمی‌شود"
                else -> "${formatNumber(q.price)} تومان"
            }
            views.setTextViewText(viewId, "$label: $value")
        }

        private fun setRefreshIntent(context: Context, views: RemoteViews) {
            val intent = Intent(context, MarketWidgetProvider::class.java).apply { action = ACTION_REFRESH }
            val pending = PendingIntent.getBroadcast(
                context, 7301, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_refresh, pending)
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
            MarketWidgetProvider.refreshAll(applicationContext)
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }
}

private fun fetchWidgetPrices(): WidgetPrices {
    val connection = URL(WIDGET_API_URL).openConnection() as HttpURLConnection
    connection.requestMethod = "GET"
    connection.connectTimeout = 8000
    connection.readTimeout = 8000
    connection.setRequestProperty("Accept", "application/json")
    connection.setRequestProperty("Cache-Control", "no-cache")
    try {
        val code = connection.responseCode
        if (code !in 200..299) throw IllegalStateException("HTTP $code")
        val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        val root = JSONObject(body)
        if (!root.optBoolean("ok")) throw IllegalStateException(root.optString("error", "پاسخ نامعتبر"))
        val items = root.optJSONObject("items") ?: JSONObject()
        val defs = listOf(
            "usd" to "دلار آزاد",
            "emami" to "سکه امامی",
            "gram" to "طلای ۱۸ عیار",
            "usdt" to "تتر"
        )
        val quotes = defs.map { (key, label) ->
            val hansha = items.optJSONObject(key)?.optJSONObject("hansha")
            val timestamp = hansha?.optString("timestamp")?.takeIf { it.isNotBlank() && it != "null" }
            val price = hansha?.numberOrNull("price")
            WidgetQuote(
                key = key,
                label = label,
                price = price,
                buy = hansha?.numberOrNull("buy"),
                timestamp = timestamp,
                fresh = price != null && isFresh(timestamp)
            )
        }
        return WidgetPrices(
            quotes = quotes,
            serverUpdatedAt = root.optString("updated_at").takeIf { it.isNotBlank() && it != "null" }
        )
    } finally {
        connection.disconnect()
    }
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
        } catch (_: Exception) { }
    }
    return null
}

private fun formatNumber(value: Double): String = DecimalFormat("#,##0.##").format(value)

private fun formatTime(value: String): String {
    val time = parseTimestamp(value) ?: return "نامشخص"
    return SimpleDateFormat("HH:mm:ss", Locale("fa", "IR")).format(Date(time))
}
