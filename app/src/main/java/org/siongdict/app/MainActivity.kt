package org.siongdict.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import org.siongdict.app.data.Simplifier
import org.siongdict.app.ui.SearchScreen
import org.siongdict.app.ui.theme.SiongTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 简体渲染的开关与字表必须在首帧之前就位，否则会闪一下繁体
        Simplifier.init(applicationContext)
        setContent {
            SiongTheme {
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
