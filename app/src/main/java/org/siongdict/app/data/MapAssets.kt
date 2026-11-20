package org.siongdict.app.data

import android.content.Context
import android.util.Log
import org.json.JSONObject
import org.siongdict.app.analysis.LonLat
import org.siongdict.app.analysis.ProvinceShape

/**
 * 離線底圖資產（assets/maps/provinces.json）的讀取與解析。
 *
 * 底圖由 tools/build_map.py 從 MCPDict 的 china.json 抽出湖南省域附近、
 * 做 Douglas–Peucker 簡化後寫入；只含省級輪廓，不含任何線上圖磚。
 * 讀取失敗（資產缺失、JSON 壞掉）時回傳空表，地圖頁降級為只畫站點。
 */
internal object MapAssets {

    private const val TAG = "MapAssets"
    private const val ASSET = "maps/provinces.json"

    private var cache: List<ProvinceShape>? = null

    fun load(context: Context): List<ProvinceShape> {
        cache?.let { return it }
        val shapes = try {
            val json = context.assets.open(ASSET).bufferedReader().use { it.readText() }
            parseProvinces(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load $ASSET", e)
            emptyList()
        }
        cache = shapes
        return shapes
    }

    /** 解析 {"provinces":[{"name":…,"polygons":[[[lon,lat],…],…]}]}；壞項逐項跳過。 */
    internal fun parseProvinces(json: String): List<ProvinceShape> {
        val root = JSONObject(json)
        val provinces = root.optJSONArray("provinces") ?: return emptyList()
        val result = ArrayList<ProvinceShape>(provinces.length())
        for (index in 0 until provinces.length()) {
            val item = provinces.optJSONObject(index) ?: continue
            val name = item.optString("name", "")
            val polygonsJson = item.optJSONArray("polygons") ?: continue
            val polygons = ArrayList<List<LonLat>>(polygonsJson.length())
            for (p in 0 until polygonsJson.length()) {
                val ring = polygonsJson.optJSONArray(p) ?: continue
                val points = ArrayList<LonLat>(ring.length())
                for (k in 0 until ring.length()) {
                    val pair = ring.optJSONArray(k) ?: continue
                    if (pair.length() < 2) continue
                    val lon = pair.optDouble(0, Double.NaN)
                    val lat = pair.optDouble(1, Double.NaN)
                    if (lon.isFinite() && lat.isFinite()) points.add(LonLat(lon, lat))
                }
                if (points.size >= 3) polygons.add(points)
            }
            if (polygons.isNotEmpty()) result.add(ProvinceShape(name, polygons))
        }
        return result
    }
}
