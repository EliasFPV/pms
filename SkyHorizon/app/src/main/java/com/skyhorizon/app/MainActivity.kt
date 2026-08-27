package com.skyhorizon.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.skyhorizon.app.ui.SkyHorizonApp
import com.skyhorizon.app.ui.components.initialiseOsmdroid
import com.skyhorizon.app.ui.theme.SkyHorizonTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // osmdroid needs its cache path and user agent before the first tile request.
        initialiseOsmdroid(applicationContext)

        setContent {
            SkyHorizonTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    SkyHorizonApp()
                }
            }
        }
    }
}
