package org.siongdict.app.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

import org.siongdict.app.data.AppInfo
import org.siongdict.app.data.LocalData

/**
 * 生产装配：一个 Activity 一个 [LocalData]，把三个端口注入 [SearchViewModel]。
 * JVM 测试不经过这里，直接用 fakes 构造 ViewModel。
 */
class SearchViewModelFactory(context: Context) : ViewModelProvider.Factory {

    private val appContext = context.applicationContext
    private val appVersion = AppInfo.versionName(appContext)
    private val data = LocalData(appContext)

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(SearchViewModel::class.java)) {
            "Unknown ViewModel class: $modelClass"
        }
        return SearchViewModel(data.maintenance, data.lexicon, data.cognateBook, appVersion) as T
    }
}
