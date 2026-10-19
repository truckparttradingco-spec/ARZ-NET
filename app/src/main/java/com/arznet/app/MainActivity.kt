package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    ArzNetHome()
                }
            }
        }
    }
}

private data class PriceRow(
    val title: String,
    val value: String,
    val detail: String
)

private fun number(value: Double): String {
    return NumberFormat.getNumberInstance(Locale.US).apply {
        maximumFractionDigits = 2
        minimumFractionDigits = 0
    }.format(value)
}

private fun getJson(url: String): JSONObject {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")

        if (connection.responseCode !in 200..299) {
            error("HTTP ${connection.responseCode}")
        }

        val body = connection.inputStream.bufferedReader().use {
            it.readText()
        }
        return JSONObject(body)
    } finally {
        connection.disconnect()
    }
}

private suspend fun loadPrices(): List<PriceRow> =
    withContext(Dispatchers.IO) {
        val result = mutableListOf<PriceRow>()

        var usdToman: Double? = null
        var gold18Toman: Double? = null
        var goldOunceUsd: Double? = null
        var btcUsd: Double? = null

        // Iranian open-market prices. Values are in toman.
        try {
            val json = getJson(
                "https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/popular.json"
            )
            val data = json.getJSONArray("data")

            for (i in 0 until data.length()) {
                val item = data.getJSONObject(i)
                when (item.optString("symbol")) {
                    "USD_IRR_FREE" ->
                        usdToman = item.optDouble("price")
                            .takeIf { it > 0.0 }

                    "GOLD_18K_IRR" ->
                        gold18Toman = item.optDouble("price")
                            .takeIf { it > 0.0 }
                }
            }

            if (usdToman != null) {
                result.add(
                    PriceRow(
                        "USD / Iran Open Market",
                        "${number(usdToman!! * 10)} IRR",
                        "Market reference: ${number(usdToman!!)} toman per USD. Buy/sell quotes are not supplied by this feed."
                    )
                )
            }
            if (gold18Toman != null) {
                result.add(
                    PriceRow(
                        "Gold 18K / gram",
                        "${number(gold18Toman!! * 10)} IRR",
                        "Iran market reference; source reports toman per gram."
                    )
                )
            }
        } catch (e: Exception) {
            result.add(
                PriceRow(
                    "Iran market data",
                    "Unavailable",
                    "Could not load the public Iran market feed."
                )
            )
        }

        // Global gold spot price, USD per troy ounce.
        try {
            val json = getJson("https://api.gold-api.com/price/XAU")
            goldOunceUsd = json.optDouble("price")
                .takeIf { it > 0.0 }

            if (goldOunceUsd != null) {
                result.add(
                    PriceRow(
                        "Gold Spot (XAU/USD)",
                        "$${number(goldOunceUsd!!)} / oz",
                        "Global spot quote in USD per troy ounce."
                    )
                )
            } else {
                result.add(
                    PriceRow(
                        "Gold Spot (XAU/USD)",
                        "Unavailable",
                        "The gold provider did not return a valid price."
                    )
                )
            }
        } catch (e: Exception) {
            result.add(
                PriceRow(
                    "Gold Spot (XAU/USD)",
                    "Unavailable",
                    "Could not connect to the global gold provider."
                )
            )
        }

        // Global Bitcoin price in USD.
        try {
            val json = getJson("https://api.gold-api.com/price/BTC")
            btcUsd = json.optDouble("price")
                .takeIf { it > 0.0 }

            if (btcUsd != null) {
                result.add(
                    PriceRow(
                        "Bitcoin (BTC/USD)",
                        "$${number(btcUsd!!)}",
                        "Global Bitcoin reference price in USD."
                    )
                )
            } else {
                result.add(
                    PriceRow(
                        "Bitcoin (BTC/USD)",
                        "Unavailable",
                        "The provider did not return a valid price."
                    )
                )
            }
        } catch (e: Exception) {
            result.add(
                PriceRow(
                    "Bitcoin (BTC/USD)",
                    "Unavailable",
                    "Could not connect to the Bitcoin price provider."
                )
            )
        }

        // Calculated prices are estimates, not local dealer quotes.
        if (goldOunceUsd != null && usdToman != null) {
            val gold24Toman =
                (goldOunceUsd!! / 31.1034768) * usdToman!!

            result.add(
                PriceRow(
                    "Gold 24K / gram (estimated)",
                    "${number(gold24Toman * 10)} IRR",
                    "Calculated from global gold spot and Iran open-market USD reference."
                )
            )
        }

        if (btcUsd != null && usdToman != null) {
            result.add(
                PriceRow(
                    "Bitcoin / IRR (estimated)",
                    "${number(btcUsd!! * usdToman!! * 10)} IRR",
                    "Calculated from BTC/USD and Iran open-market USD reference."
                )
            )
        }

        result.add(
            PriceRow(
                "USD / Herat Market",
                "No verified feed",
                "A dedicated Herat market source is still needed."
            )
        )
        result.add(
            PriceRow(
                "USD / Iraq Market",
                "No verified feed",
                "A dedicated Iraq market source is still needed."
            )
        )

        result
    }

@Composable
fun ArzNetHome() {
    var rows by remember { mutableStateOf(emptyList<PriceRow>()) }
    var loading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }

    LaunchedEffect(refreshKey) {
        loading = true
        errorMessage = null
        try {
            rows = loadPrices()
            if (rows.none { it.value != "Unavailable" }) {
                errorMessage = "No live prices could be loaded. Check your internet connection."
            }
        } catch (e: Exception) {
            errorMessage = e.message ?: "Unable to load prices."
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "ARZ-NET",
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(
                start = 20.dp,
                top = 24.dp,
                bottom = 4.dp
            )
        )

        Text(
            text = "Global prices & currency markets",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(
                start = 20.dp,
                bottom = 12.dp
            )
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Button(
                onClick = { refreshKey++ },
                enabled = !loading
            ) {
                Text(if (loading) "Updating..." else "Refresh prices")
            }
        }

        errorMessage?.let {
            Text(
                text = it,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(16.dp)
            )
        }

        if (loading && rows.isEmpty()) {
            Text(
                text = "Loading market data...",
                modifier = Modifier.padding(16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 24.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(rows) { item ->
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = item.title,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = item.value,
                            style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            text = item.detail,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
