package com.cryptoradar

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import com.cryptoradar.radar.LiveRadarScreen
import com.cryptoradar.radar.RadarViewModel

class MainActivity : ComponentActivity() {
    private val radarViewModel by lazy { RadarViewModel() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface { LiveRadarScreen(radarViewModel) }
            }
        }
    }
}
