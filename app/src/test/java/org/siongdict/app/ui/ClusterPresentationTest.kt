package org.siongdict.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.ClusterPartition
import org.siongdict.app.analysis.ClusterPoint

/** 聚类呈现模型：图例、站点过滤、选中详情与分区兜底（纯函数，JVM 测试）。 */
class ClusterPresentationTest {

    private fun point(lang: String, tags: Int, entries: Int, lon: Double?, lat: Double?) = ClusterPoint(
        lang = lang,
        division = "湘語·$lang",
        colorHex = "#1E88E5",
        tagCount = tags,
        entryCount = entries,
        x = 0.5f,
        y = 0.5f,
        z = 0.5f,
        lon = lon,
        lat = lat,
    )

    private val points = listOf(
        point("甲", 3, 5, 112.5, 28.0),
        point("乙", 2, 2, 113.0, 28.5),
        point("丙", 4, 6, null, 29.0),
        point("丁", 1, 1, 114.0, null),
    )

    private val communities = ClusterPartition(2, listOf(listOf(0, 1), listOf(2, 3)), 0.33f)
    private val manual = ClusterPartition(2, listOf(listOf(0, 2), listOf(1, 3)), 0.40f)

    private fun result(sparse: Boolean = false) = ClusterAnalysisResult(
        points = points,
        partitions = listOf(communities, manual),
        communities = communities,
        modularity = 0.42f,
        optimalK = 2,
        optimalSilhouette = 0.33f,
        totalTags = 10,
        sparse = sparse,
        visibleDialects = 6,
        excludedNoCognates = 1,
        excludedSparse = 1,
    )

    @Test
    fun summaryFormatsScoresToTwoDecimals() {
        val summary = presentClusters(result(), manual, null).summary
        assertEquals("模組度 0.42 · 輪廓係數 0.33", summary.line)
        assertEquals(0.42f, summary.modularity)
        assertEquals(0.33f, summary.silhouette)
        assertEquals(false, summary.sparse)
        assertNull(summary.sparseHint)
    }

    @Test
    fun sparseResultCarriesExactHint() {
        val summary = presentClusters(result(sparse = true), manual, null).summary
        assertEquals(true, summary.sparse)
        assertEquals("分群結構偏弱（輪廓係數 < 0.10），結果僅供參考。", summary.sparseHint)
    }

    @Test
    fun legendFollowsChosenPartitionAndPalette() {
        val presentation = presentClusters(result(), manual, null)
        assertEquals(2, presentation.legend.size)
        assertEquals("第 1 類 · 2 點", presentation.legend[0].label)
        assertEquals(2, presentation.legend[0].pointCount)
        assertEquals(paletteColor(0), presentation.legend[0].color)
        assertEquals(paletteColor(1), presentation.legend[1].color)
        assertEquals(listOf(paletteColor(0), paletteColor(1)), presentation.clusterColors)
    }

    @Test
    fun sitesSkipPointsMissingCoordinates() {
        val presentation = presentClusters(result(), manual, null)
        assertEquals(listOf("甲", "乙"), presentation.sites.map { it.lang })
        assertEquals(listOf("丙", "丁"), presentation.excludedWithoutLocation)
        assertEquals("#1E88E5", presentation.sites[0].colorHex)
    }

    @Test
    fun siteClusterComesFromChosenPartition() {
        val manualSites = presentClusters(result(), manual, null).sites.associate { it.lang to it.cluster }
        assertEquals(0, manualSites["甲"])
        assertEquals(1, manualSites["乙"])
        val autoSites = presentClusters(result(), null, null).sites.associate { it.lang to it.cluster }
        assertEquals(0, autoSites["甲"])
        assertEquals(0, autoSites["乙"])
        assertEquals(mapOf("甲" to 0, "乙" to 0, "丙" to 1, "丁" to 1), presentClusters(result(), null, null).clusterOf)
    }

    @Test
    fun selectionUsesPointMetricsAndMapCombinedLine() {
        val selection = presentClusters(result(), manual, "甲").selection
        assertEquals("甲", selection?.lang)
        assertEquals("甲（湘語·甲）", selection?.titleLine)
        assertEquals("義項 3 個 · 同源詞條目 5 條", selection?.metricLine)
        assertEquals("再點一次節點取消選取", selection?.optionLine)
        assertEquals("甲（湘語·甲） · 義項 3 個 · 同源詞條目 5 條", selection?.combinedLine)
    }

    @Test
    fun selectionIsNullWhenNothingOrUnknownSelected() {
        assertNull(presentClusters(result(), manual, null).selection)
        assertNull(presentClusters(result(), manual, "不存在").selection)
    }

    @Test
    fun nullPartitionFallsBackToAutomaticCommunities() {
        val presentation = presentClusters(result(), null, null)
        assertEquals(communities, presentation.partition)
        assertEquals(result().communities, presentation.partition)
        assertTrue(presentation.clusterColors.isNotEmpty())
    }

    @Test
    fun clusterColorsMatchLegendSize() {
        val presentation = presentClusters(result(), manual, null)
        assertEquals(presentation.legend.size, presentation.clusterColors.size)
        presentation.legend.forEach { item ->
            assertEquals(paletteColor(item.clusterIndex), item.color)
        }
    }
}
