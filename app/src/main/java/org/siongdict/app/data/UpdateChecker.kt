package org.siongdict.app.data

import android.util.Log
import java.net.HttpURLConnection
import java.net.URL

/**
 * 启动时与手动「檢查更新」共用的 GitHub Release 查询。
 *
 * 只取最新一版（[LATEST_RELEASE_URL]，GitHub 的 `/releases/latest` 已排除 draft 与 prerelease），
 * 不下载任何内容、不上传任何资料；失败一律静默（[UpdateResult.error] 只在「關於」面板里显示）。
 */
data class UpdateResult(
    val latestTag: String?,
    val newer: Boolean,
    val error: String?,
)

internal object UpdateChecker {

    private const val TAG = "UpdateChecker"
    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/xiang9916/SiongDict/releases/latest"
    private const val TIMEOUT_MS = 8_000

    /** 阻塞查询；调用方负责放到 IO 线程。 */
    fun check(currentVersion: String): UpdateResult {
        return try {
            val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "SiongDict/${currentVersion.ifBlank { "unknown" }}")
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    return UpdateResult(null, false, "HTTP $code")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                val tag = parseLatestTag(body)
                    ?: return UpdateResult(null, false, "無法解析版本資訊")
                val newer = currentVersion.isNotBlank() && compareVersions(currentVersion, tag) < 0
                UpdateResult(tag, newer, null)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "檢查更新失敗", e)
            UpdateResult(null, false, e.message ?: "網路錯誤")
        }
    }

    /** 从 GitHub 的 JSON 里取 `"tag_name": "0.5-alpha.3"`；纯函数，便于 JVM 单测。 */
    fun parseLatestTag(json: String): String? =
        Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"").find(json)?.groupValues?.get(1)

    /**
     * 比较两个版本字符串：先比数字段（按 . 切分、逐段数值比较），
     * 再比预发布后缀（`0.4-rc.14` < `0.5-alpha.1`；同一数字段下带后缀 < 不带后缀）。
     *
     * @return 负数表示 a 更旧、0 相等、正数表示 a 更新。
     */
    fun compareVersions(a: String, b: String): Int {
        val (aNumbers, aPre) = splitVersion(a)
        val (bNumbers, bPre) = splitVersion(b)
        val numberResult = compareNumbers(aNumbers, bNumbers)
        if (numberResult != 0) return numberResult
        if (aPre == null && bPre == null) return 0
        if (aPre == null) return 1
        if (bPre == null) return -1
        return comparePrerelease(aPre, bPre)
    }

    private fun splitVersion(version: String): Pair<List<Int>, String?> {
        val trimmed = version.trim()
        val dash = trimmed.indexOf('-')
        val numbersPart = if (dash >= 0) trimmed.substring(0, dash) else trimmed
        val pre = if (dash >= 0) trimmed.substring(dash + 1) else null
        val numbers = numbersPart.split('.').map { it.toIntOrNull() ?: 0 }
        return numbers to pre
    }

    private fun compareNumbers(a: List<Int>, b: List<Int>): Int {
        val size = maxOf(a.size, b.size)
        for (i in 0 until size) {
            val left = a.getOrElse(i) { 0 }
            val right = b.getOrElse(i) { 0 }
            if (left != right) return left.compareTo(right)
        }
        return 0
    }

    /** 逐段比较预发布后缀：数字段按数值比，字母段按字典序；数字段比字母段「更新」。 */
    private fun comparePrerelease(a: String, b: String): Int {
        val aParts = a.split('.', '-')
        val bParts = b.split('.', '-')
        val size = maxOf(aParts.size, bParts.size)
        for (i in 0 until size) {
            val left = aParts.getOrNull(i) ?: return -1
            val right = bParts.getOrNull(i) ?: return 1
            val leftNumber = left.toIntOrNull()
            val rightNumber = right.toIntOrNull()
            val result = when {
                leftNumber != null && rightNumber != null -> leftNumber.compareTo(rightNumber)
                leftNumber != null -> 1
                rightNumber != null -> -1
                else -> left.compareTo(right)
            }
            if (result != 0) return result
        }
        return 0
    }
}
