package com.bbcl.gpscamera

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color as ComposeColor

private val Scheme = darkColorScheme(
    primary = ComposeColor(0xFFFFB4AA),
    onPrimary = ComposeColor(0xFF690005),
    primaryContainer = ComposeColor(0xFF93000A),
    onPrimaryContainer = ComposeColor(0xFFFFDAD5),
    secondaryContainer = ComposeColor(0xFF5D3F3B),
    onSecondaryContainer = ComposeColor(0xFFFFDAD5),
    background = ComposeColor.Black,
    surface = ComposeColor(0xFF1E1B1A),
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = Scheme) {
                val model = remember { CameraModel(this) }
                var gallery by rememberSaveable { mutableStateOf(false) }
                if (gallery) {
                    GalleryScreen(onBack = {
                        gallery = false
                        model.refreshLastMedia() // photos may have been deleted
                    })
                } else {
                    CameraScreen(model, onOpenGallery = { gallery = true })
                }
            }
        }
    }
}
