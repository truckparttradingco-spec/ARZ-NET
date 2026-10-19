package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShowChart
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.NumberFormat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

private val Navy = Color(0xFF101D35)
private val Blue = Color(0xFF2563EB)
private val Page = Color(0xFFF4F6FA)
private val Green = Color(0xFF15966A)
private val Red = Color(0xFFD94747)
private const val OUNCE_GRAMS = 31.1034768
private const val REFRESH_MS = 15_000L
// Add a Trading Economics API key here only for local testing; avoid committing private keys to a public repository.
private const val TE_API_KEY = ""

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
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

private data class Quote(
    val id: String,
    val title: String,
    val flag: String,
    val price: Double?,
    val unit: String,
    val source: String,
    val note: String = "",
    val history: List<Double> = emptyList()
)

private data class FeedResult(
    val usdToman: Double?,
    val gold18Toman: Double?,
    val mesghalToman: Double?,
    val tetherToman: Double?
)

private fun fmt(value: Double, digits: Int = 0): String =
    NumberFormat.getNumberInstance(Locale("fa", "IR")).apply {
        maximumFractionDigits = digits
        minimumFractionDigits = 0
    }.format(value)

private fun getJson(url: String): JSONObject {
    val connection = (URL(url).openConnection() as HttpURLConnection).apply {
        requestMethod = "GET"
        connectTimeout = 10_000
