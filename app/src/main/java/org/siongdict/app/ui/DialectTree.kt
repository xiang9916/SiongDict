package org.siongdict.app.ui

/**
 * 方言筛选树：由「簡稱 → 音典分區」映射构建的只读层级结构。
 *
 * 音典分區以「－」分层（如 [湘贛－岳州]、[中上江－荆益－江漢]）。UI 只展示三级，
 * 所以树的层级上限为 [MAX_DIALECT_DEPTH]；更深的层级（目前实测最多四层）会并入第三级节点的点数，
 * 不单独成层。所有函数都是纯函数，便于 JVM 单元测试。
 */
data class DialectNode(
    val name: String,
    val path: String,
    val pointCount: Int,
    val children: List<DialectNode>,
)

/** 分区字符串的分隔符（全角连字符 U+FF0D）。 */
internal const val DIVISION_SEPARATOR = "－"

/** 树的最大层级：一级／二级／三级。 */
internal const val MAX_DIALECT_DEPTH = 3

/**
 * 分区字符串的叶子键：trim 后按「－」切段，最多保留前三段；第四段起不再成层，
 * 但由 [buildDialectTree] 计入第三级节点的点数。空分区返回空串。
 */
fun leafKey(division: String): String = splitDivision(division).take(MAX_DIALECT_DEPTH)
    .joinToString(DIVISION_SEPARATOR)

/** 把分区字符串切成非空白段；全空时返回空列表。 */
private fun splitDivision(division: String): List<String> =
    division.split(DIVISION_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

/**
 * 按分区路径构建方言树，保持 [divisions] 的迭代顺序（同层节点按首次出现的次序），
 * 同一分区下的多个方言点累加到路径上每个节点的 [DialectNode.pointCount]。
 */
fun buildDialectTree(divisions: Map<String, String>): List<DialectNode> {
    val roots = LinkedHashMap<String, MutableDialectNode>()
    for (division in divisions.values) {
        val parts = splitDivision(division).take(MAX_DIALECT_DEPTH)
        if (parts.isEmpty()) continue
        var level = roots
        var path = ""
        for (part in parts) {
            path = if (path.isEmpty()) part else path + DIVISION_SEPARATOR + part
            val node = level.getOrPut(part) { MutableDialectNode(part, path) }
            node.pointCount++
            level = node.children
        }
    }
    return roots.values.map { it.freeze() }
}

/** 树的全部叶子路径（可勾选的最小单位）。 */
fun allLeafPaths(tree: List<DialectNode>): Set<String> =
    tree.flatMap { nodeLeafPaths(it) }.toSet()

/** 节点下全部叶子路径；叶子节点返回自身路径。 */
fun nodeLeafPaths(node: DialectNode): Set<String> =
    if (node.children.isEmpty()) setOf(node.path)
    else node.children.flatMap { nodeLeafPaths(it) }.toSet()

/**
 * 切换一组叶子分区的选中状态。[selected] 为 null 表示全部显示（未筛选）。
 * 结果等于全部叶子时正规化回 null；叶子集合为空时原样返回。
 */
fun toggleLeaves(
    selected: Set<String>?,
    nodeLeaves: Set<String>,
    allLeaves: Set<String>,
): Set<String>? {
    if (nodeLeaves.isEmpty()) return selected
    val current = selected ?: allLeaves
    val next = if (current.containsAll(nodeLeaves)) current - nodeLeaves else current + nodeLeaves
    return if (next.containsAll(allLeaves)) null else next
}

/** 树摊平为「节点 to 深度」的显示行，父节点在前，深度从 0 起。 */
fun flattenDialectTree(tree: List<DialectNode>, depth: Int = 0): List<Pair<DialectNode, Int>> =
    tree.flatMap { node -> listOf(node to depth) + flattenDialectTree(node.children, depth + 1) }

/** 构建期用的可变节点；freeze() 后转成不可变的 [DialectNode]。 */
private class MutableDialectNode(val name: String, val path: String) {
    var pointCount = 0
    val children = LinkedHashMap<String, MutableDialectNode>()

    fun freeze(): DialectNode = DialectNode(
        name = name,
        path = path,
        pointCount = pointCount,
        children = children.values.map { it.freeze() },
    )
}
