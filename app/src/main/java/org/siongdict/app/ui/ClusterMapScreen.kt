package org.siongdict.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.siongdict.app.analysis.ClusterAnalysisResult
import org.siongdict.app.analysis.MapSite
import org.siongdict.app.data.MapAssets

/**
 * 「在地圖展示」全屏页：把聚类结果摊到二维地图上（研究 → 聚類分析 → 在地圖展示）。
 *
 * 底圖是離線簡化的省級輪廓（assets/maps/provinces.json），站點按 info 表的經緯度定位；
 * 泰森多邊形用類別色淡填，直觀看出各類別在地理上的勢力範圍。
 * 返回鍵由上層 [ClusterAnalysisScreen] 接管（先退出地圖頁，再退出聚類頁）。
 */
@Composable
internal fun ClusterMapScreen(
    state: ClusterUiState,
    result: ClusterAnalysisResult,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val shapes = remember { MapAssets.load(context) }
    var showLabels by remember(result) { mutableStateOf(true) }
    var showVoronoi by remember(result) { mutableStateOf(true) }
    var selectedLang by remember(result) { mutableStateOf<String?>(null) }

    val partition = state.partition ?: result.communities
    val clusterOf = remember(partition, result.points) {
        val map = HashMap<String, Int>()
        partition.clusters.forEachIndexed { index, cluster ->
            cluster.forEach { pointIndex ->
                result.points.getOrNull(pointIndex)?.let { map[it.lang] = index }
            }
        }
        map
    }
    val sites = remember(result.points, clusterOf) {
        result.points.mapNotNull { point ->
            val lon = point.lon ?: return@mapNotNull null
            val lat = point.lat ?: return@mapNotNull null
            MapSite(
                lang = point.lang,
                lon = lon,
                lat = lat,
                cluster = clusterOf[point.lang] ?: 0,
                colorHex = point.colorHex,
            )
        }
    }
    val clusterColors = remember(partition) {
        List(partition.k.coerceAtLeast(1)) { index -> Color(paletteColor(index)) }
    }
    val selected = sites.firstOrNull { it.lang == selectedLang }

    Surface(
        color = MaterialTheme.colorScheme.background,
        modifier = Modifier
            .fillMaxSize()
            .testTag("cluster_map_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(disp("地圖展示"), fontSize = 20.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = onClose, modifier = Modifier.testTag("cluster_map_close")) {
                    Text(disp("關閉"))
                }
            }
            HorizontalDivider()

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(disp("顯示名稱"), fontSize = 13.sp)
                    Switch(
                        checked = showLabels,
                        onCheckedChange = { showLabels = it },
                        modifier = Modifier.testTag("map_labels_switch")
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(disp("泰森多邊形"), fontSize = 13.sp)
                    Switch(
                        checked = showVoronoi,
                        onCheckedChange = { showVoronoi = it },
                        modifier = Modifier.testTag("map_voronoi_switch")
                    )
                }
            }

            if (sites.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        disp("沒有可定位的方言點（info 表缺少經緯度）。"),
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(horizontal = 12.dp)
                ) {
                    GeoMapView(
                        sites = sites,
                        shapes = shapes,
                        clusterColors = clusterColors,
                        selectedLang = selectedLang,
                        showLabels = showLabels,
                        showVoronoi = showVoronoi,
                        onSelect = { selectedLang = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }

            if (shapes.isEmpty()) {
                Text(
                    text = disp("底圖資產缺失，僅顯示站點位置。"),
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                itemsIndexed(partition.clusters, key = { index, _ -> index }) { index, cluster ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = clusterColors.getOrElse(index) { Color.Gray }.copy(alpha = 0.16f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(
                                        clusterColors.getOrElse(index) { Color.Gray },
                                        CircleShape
                                    )
                            )
                            Text(
                                disp("第 ${index + 1} 類 · ${cluster.size} 點"),
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }

            val detail = selected?.let { site ->
                val point = result.points.firstOrNull { it.lang == site.lang }
                if (point == null) {
                    disp(site.lang)
                } else {
                    disp("${site.lang}（${point.division}） · 義項 ${point.tagCount} 個 · 同源詞條目 ${point.entryCount} 條")
                }
            }
            Text(
                text = detail ?: disp("輕點節點查看方言點"),
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 6.dp)
            )
        }
    }
}
