package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val Navy = Color(0xFF101A2B)
private val Panel = Color(0xFF1A263A)
private val Muted = Color(0xFF9AAAC0)
private val Green = Color(0xFF35C995)
private val Red = Color(0xFFFF6B75)
private val Gold = Color(0xFFFFC857)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(16, 26, 43)
        window.navigationBarColor = android.graphics.Color.rgb(16, 26, 43)
        setContent { ArzNetApp() }
    }
}

private data class Quote(
    val id: String,
    val title: String,
    val symbol: String,
    val flag: String,
    val value: Double?,
    val unit: String,
    val source: String,
    val updatedAt: Long = System.currentTimeMillis(),
    val history: List<Double> = emptyList(),
    val note: String = ""
)

private fun fmt(v: Double): String = NumberFormat.getNumberInstance(Locale.US).apply {
    maximumFractionDigits = 2
    minimumFractionDigits = 0
}.format(v)

private fun request(url: String): String {
    val c = (URL(url).openConnection() as HttpURLConnection)
    try {
        c.requestMethod = "GET"
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "ARZ-NET-Android/1.1")
        if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
        return c.inputStream.bufferedReader().use { it.readText() }
    } finally { c.disconnect() }
}

private suspend fun loadQuotes(previous: List<Quote>): List<Quote> = withContext(Dispatchers.IO) {
    var usdToman: Double? = null
    var gold18Toman: Double? = null
    var goldOzUsd: Double? = null
    var btcUsd: Double? = null
    var tetherUsd: Double? = null
    val sources = mutableMapOf<String, String>()

    // Iran-market feed reports toman, not rial.
    try {
        val json = JSONObject(request("https://raw.githubusercontent.com/iran-market/iran-market.github.io/main/data/popular.json"))
        val data = json.optJSONArray("data") ?: JSONArray()
