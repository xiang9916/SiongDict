package org.siongdict.app.ui

import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterPartition
import org.siongdict.app.analysis.MapSite
import java.util.Locale

/**
 * 聚类结果的呈现模型（纯 Kotlin，无 Compose 依赖，可在 JVM 上测试）。
 *
 * 把「一次聚类结果 + 当前分区 + 当前选中点」翻译成两个页面都要用的展示数据：
 * 摘要行、类别图例、类别颜色、站点、选中详情。以前这段派生逻辑在
 * [ClusterAnalysisScreen] 与 [ClusterMapScreen] 里各写一份（分区兜底、clusterOf、
 * 站点过滤、类别色、图例文案、详情文案），现在只有这里一份。
 *
 * 点名称标签仍直接用 [org.siongdict.app.analysis.ClusterPoint.lang]，不在此二次派生。
 */
internal data class ClusterLegendItem(
    /** 类别序号（0 起），对应分区 clusters 的下标。 */
    val clusterIndex: Int,
    /** 展示文案，如「第 1 類 · 178 點」。 */
    val label: String,
    val pointCount: Int,
    /** 类别色（0xAARRGGBB），由固定调色盘按序号取。 */
    val color: Long,
)

/** 选中方言点的详情文案（聚类页分两行、地图页合成一行）。 */
internal data class ClusterSelection(
    val lang: String,
    val division: String,
    /** 「岳陽路口（…）」 */
    val titleLine: String,
    /** 「義項 3 個 · 同源詞條目 5 條」 */
    val metricLine: String,
    /** 「再點一次節點取消選取」 */
    val optionLine: String,
) {
    /** 地图页把标题与指标合成一行，与旧版地图页文案逐字一致。 */
    val combinedLine: String get() = "$titleLine · $metricLine"
}

/** 分群质量摘要：一行加粗指标 + 稀疏提示。 */
internal data class ClusterSummary(
    val modularity: Float,
    val silhouette: Float,
    /** 「模組度 0.10 · 輪廓係數 0.20」 */
    val line: String,
    val sparse: Boolean,
    /** 稀疏时给「分群結構偏弱（輪廓係數 < 0.10）…」，否则 null。 */
    val sparseHint: String?,
)

/** 一次聚类结果在两个页面上的全部呈现数据。 */
internal data class ClusterPresentation(
    /** 实际采用的分区（外部未给时退回自动分群）。 */
    val partition: ClusterPartition,
    val summary: ClusterSummary,
    val legend: List<ClusterLegendItem>,
    /** 每个类别的颜色（长度 = 图例长度，至少 1）。 */
    val clusterColors: List<Long>,
    /** 方言点 → 类别序号。 */
    val clusterOf: Map<String, Int>,
    /** 能定位的站点（经纬度都非空）。 */
    val sites: List<MapSite>,
    /** 因缺经纬度而无法上图、被排除的方言点。 */
    val excludedWithoutLocation: List<String>,
    /** 选中点详情；未选或选了不存在的点时 null。 */
    val selection: ClusterSelection?,
)

/**
 * 构造呈现数据。纯函数：同样的输入必得同样的输出，不读库、不依赖 Compose。
 */
internal fun presentClusters(
    result: ClusterAnalysisResult,
    partition: ClusterPartition?,
    selectedLang: String?,
): ClusterPresentation {
    val effective = partition ?: result.communities
    val clusterOf = LinkedHashMap<String, Int>()
    effective.clusters.forEachIndexed { index, cluster ->
        cluster.forEach { pointIndex ->
            result.points.getOrNull(pointIndex)?.let { clusterOf[it.lang] = index }
        }
    }

    val sites = ArrayList<MapSite>(result.points.size)
    val withoutLocation = ArrayList<String>()
    result.points.forEach { point ->
        val lon = point.lon
        val lat = point.lat
        if (lon == null || lat == null) {
            withoutLocation += point.lang
        } else {
            sites += MapSite(
                lang = point.lang,
                lon = lon,
                lat = lat,
                cluster = clusterOf[point.lang] ?: 0,
                colorHex = point.colorHex,
            )
        }
    }

    val legend = effective.clusters.mapIndexed { index, cluster ->
        ClusterLegendItem(
            clusterIndex = index,
            label = "第 ${index + 1} 類 · ${cluster.size} 點",
            pointCount = cluster.size,
            color = paletteColor(index),
        )
    }

    val selection = selectedLang
        ?.let { lang -> result.points.firstOrNull { it.lang == lang } }
        ?.let { point ->
            ClusterSelection(
                lang = point.lang,
                division = point.division,
                titleLine = "${point.lang}（${point.division}）",
                metricLine = "義項 ${point.tagCount} 個 · 同源詞條目 ${point.entryCount} 條",
                optionLine = "再點一次節點取消選取",
            )
        }

    return ClusterPresentation(
        partition = effective,
        summary = ClusterSummary(
            modularity = result.modularity,
            silhouette = result.optimalSilhouette,
            line = "模組度 ${score(result.modularity)} · 輪廓係數 ${score(result.optimalSilhouette)}",
            sparse = result.sparse,
            sparseHint = if (result.sparse) {
                "分群結構偏弱（輪廓係數 < 0.10），結果僅供參考。"
            } else {
                null
            },
        ),
        legend = legend,
        clusterColors = List(legend.size.coerceAtLeast(1)) { paletteColor(it) },
        clusterOf = clusterOf,
        sites = sites,
        excludedWithoutLocation = withoutLocation,
        selection = selection,
    )
}

/** 分数显示：固定两位小数（摘要行与旧版逐字一致）。 */
private fun score(value: Float): String = String.format(Locale.ROOT, "%.2f", value)
