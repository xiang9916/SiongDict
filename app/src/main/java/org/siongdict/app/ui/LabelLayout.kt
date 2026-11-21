package org.siongdict.app.ui

/**
 * 一个待放置的标签：调用方已经测量好的矩形（画布像素坐标）。
 *
 * [force] = true 时即使与已放置的标签重叠也照样放下（用于选中点／选中站点）。
 * 列表顺序即优先级：调用方按希望的放置次序传入（三維画布按深度由近到远、地圖按站点序）。
 */
internal data class LabelRequest(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val force: Boolean = false,
) {
    val right: Float get() = left + width
    val bottom: Float get() = top + height

    /** 严格重叠：边贴边不算重叠。 */
    internal fun overlaps(other: LabelRequest): Boolean =
        left < other.right && other.left < right && top < other.bottom && other.top < bottom
}

/**
 * 贪心标签避让：按 [requests] 的顺序依次放置，与已放置矩形重叠者跳过
 * （[LabelRequest.force] 例外），放满 [maxLabels] 个后停止。
 *
 * 返回放下的下标，顺序与 [requests] 一致。只做几何判定，不涉及文字测量与绘制；
 * 三維散点画布与地圖画布共用这一份规则。
 *
 * @param avoidOverlap 为 false 时不做重叠判定（点数少时允许标签互相压住，保持旧观感）。
 */
internal fun placeLabels(
    requests: List<LabelRequest>,
    maxLabels: Int,
    avoidOverlap: Boolean = true,
): List<Int> {
    if (maxLabels <= 0 || requests.isEmpty()) return emptyList()
    val capacity = minOf(requests.size, maxLabels)
    val placed = ArrayList<LabelRequest>(capacity)
    val result = ArrayList<Int>(capacity)
    for ((index, request) in requests.withIndex()) {
        if (placed.size >= maxLabels) break
        if (avoidOverlap && !request.force && placed.any { it.overlaps(request) }) continue
        placed.add(request)
        result.add(index)
    }
    return result
}
