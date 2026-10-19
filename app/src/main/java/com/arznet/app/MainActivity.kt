package com.arznet.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ArzNetHome()
                }
            }
        }
    }
}

@Composable
fun ArzNetHome() {
    val items = listOf(
        "Gold Spot (XAU/USD)" to "Waiting for data source",
        "Bitcoin (BTC/USD)" to "Waiting for data source",
        "USD / Iranian Rial" to "Waiting for data source",
        "USD / Herat Market" to "Waiting for data source",
        "USD / Iraq Market" to "Waiting for data source",
        "Gold 24K (IRR / gram)" to "Calculated when live rates are available",
        "Gold 18K (IRR / gram)" to "Calculated when live rates are available"
    )

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
                bottom = 16.dp
            )
        )

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                bottom = 20.dp
            ),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(items.size) { index ->
                val item = items[index]

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(
                        defaultElevation = 2.dp
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = item.first,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = item.second,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                }
            }
        }
    }
}
