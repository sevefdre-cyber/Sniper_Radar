package com.cryptoradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.cryptoradar.history.BacktestHistoryScreen
import com.cryptoradar.radar.LiveRadarScreen
import com.cryptoradar.radar.RadarViewModel

class MainActivity : ComponentActivity() {
    private val radarViewModel by lazy { RadarViewModel() }
    private var selectedTab by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        selectedTab = savedInstanceState?.getInt("selectedTab") ?: 0
        setContent {
            MaterialTheme {
                Surface {
                    Column {
                        TabRow(selectedTabIndex = selectedTab) {
                            Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }, text = { Text("Radar") })
                            Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }, text = { Text("Historique & backtest") })
                        }
                        if (selectedTab == 0) LiveRadarScreen(radarViewModel) else BacktestHistoryScreen()
                    }
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("selectedTab", selectedTab)
        super.onSaveInstanceState(outState)
    }
}
