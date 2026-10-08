package org.siongdict.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import org.siongdict.app.data.Simplifier
import org.siongdict.app.ui.SearchScreen

private val SiongDarkRed = Color(0xFF8B0000)
private val SiongCrimson = Color(0xFFDC143C)

private val LightColors = lightColorScheme(
    primary = SiongDarkRed,
    background = Color(0xFFF2EEE6),
)

private val DarkColors = darkColorScheme(
    primary = SiongCrimson,
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 简体渲染的开关与字表必须在首帧之前就位，否则会闪一下繁体
        Simplifier.init(applicationContext)
        setContent {
            val darkTheme = isSystemInDarkTheme()
            MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SearchScreen()
                }
            }
        }
    }
}
