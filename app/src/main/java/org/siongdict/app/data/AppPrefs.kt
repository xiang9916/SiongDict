package org.siongdict.app.data

/**
 * 应用偏好档与键名的唯一定义处。
 * 版本记录（[DatabaseFiles]）与简体渲染开关（[Simplifier]）共用同一个档，
 * 但分属不同的键，互不干扰。
 */
internal object AppPrefs {
    const val FILE = "siongdict_prefs"
    const val KEY_APP_VERSION = "app_version"
    const val KEY_RENDER_SIMPLIFIED = "render_simplified"

    /** 最近一次从 GitHub 查到的最新版本 tag（见 UpdateState）。 */
    const val KEY_UPDATE_LATEST = "update_latest"

    /** 护眼模式开关（见 EyeCare）。 */
    const val KEY_EYE_CARE = "eye_care"
}
