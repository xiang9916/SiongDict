package org.siongdict.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import org.siongdict.app.data.AppInfo
import org.siongdict.app.data.DisplayPreferences
import org.siongdict.app.data.SharedPrefsStore
import org.siongdict.app.data.UpdateChecker
import org.siongdict.app.data.UpdateStatus
import org.siongdict.app.data.loadSimplifierTable
import org.siongdict.app.ui.LocalDisplayPreferences
import org.siongdict.app.ui.LocalUpdateStatus
import org.siongdict.app.ui.SearchScreen
import org.siongdict.app.ui.SearchViewModelFactory
import org.siongdict.app.ui.theme.SiongTheme

class MainActivity : ComponentActivity() {

    private val viewModelFactory by lazy { SearchViewModelFactory(applicationContext) }

    /** 显示偏好（簡体渲染／護眼）：首帧前初始化，之后经 CompositionLocal 提供给界面。 */
    private val displayPreferences by lazy {
        DisplayPreferences(SharedPrefsStore(applicationContext)) {
            loadSimplifierTable(applicationContext)
        }
    }

    /** 「檢查更新」状态：启动时自动查一次，面板里可手动重试。 */
    private val updateStatus by lazy {
        UpdateStatus(
            store = SharedPrefsStore(applicationContext),
            checker = { version -> UpdateChecker.check(version) },
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // 简体渲染的开关与字表必须在首帧之前就位，否则会闪一下繁体
        displayPreferences.init()
        updateStatus.init()
        lifecycleScope.launch {
            updateStatus.refresh(AppInfo.versionName(applicationContext))
        }
        setContent {
            CompositionLocalProvider(
                LocalDisplayPreferences provides displayPreferences,
                LocalUpdateStatus provides updateStatus,
            ) {
                SiongTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        SearchScreen(viewModel = viewModel(factory = viewModelFactory))
                    }
                }
            }
        }
    }
}
