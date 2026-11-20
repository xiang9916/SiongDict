package org.siongdict.app.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 护眼模式：开启后界面底色用淡黄（0xFFF2EEE6），关闭时用纯白。
 *
 * 与 [Simplifier] 同一套做法——单例持有一份 Compose 状态，主题在组合里读它，
 * 所以开关一拨全 App（含各弹窗）立即换底色，不需要重启或重新装配。
 */
internal object EyeCare {

    private var enabledState by mutableStateOf(false)

    val enabled: Boolean get() = enabledState

    fun init(ctx: Context) {
        enabledState = prefs(ctx).getBoolean(AppPrefs.KEY_EYE_CARE, false)
    }

    fun setEnabled(ctx: Context, on: Boolean) {
        enabledState = on
        prefs(ctx).edit().putBoolean(AppPrefs.KEY_EYE_CARE, on).apply()
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(AppPrefs.FILE, Context.MODE_PRIVATE)
}
