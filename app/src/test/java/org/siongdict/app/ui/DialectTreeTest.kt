package org.siongdict.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DialectTreeTest {

    @Test
    fun buildsTreeWithParentsAndAccumulatedPointCounts() {
        val divisions = linkedMapOf(
            "甲" to "湘贛－岳州",
            "乙" to "湘贛－岳州",
            "丙" to "湘贛－北湘－長益",
            "丁" to "湘南"
        )
        val tree = buildDialectTree(divisions)

        assertEquals(listOf("湘贛", "湘南"), tree.map { it.name })
        assertEquals(3, tree[0].pointCount)
        assertEquals(2, tree[0].children.size)
        assertEquals("岳州", tree[0].children[0].name)
        assertEquals("湘贛－岳州", tree[0].children[0].path)
        assertEquals(2, tree[0].children[0].pointCount)
        assertEquals(1, tree[0].children[1].children.single().pointCount)
        assertEquals(1, tree[1].pointCount)
        assertTrue("父亲节点不是叶子", tree[1].children.isEmpty())

        assertEquals(
            setOf("湘贛－岳州", "湘贛－北湘－長益", "湘南"),
            allLeafPaths(tree)
        )
        assertEquals(setOf("湘南"), nodeLeafPaths(tree[1]))
    }

    @Test
    fun foldsDeeperDivisionsIntoThirdLevel() {
        val tree = buildDialectTree(
            linkedMapOf(
                "甲" to "中上江－荆益－江漢－漢陽",
                "乙" to "中上江－荆益－江漢"
            )
        )
        val node = tree.single().children.single().children.single()

        assertEquals("江漢", node.name)
        assertEquals(2, node.pointCount)
        assertTrue("第四层不得单独成层", node.children.isEmpty())
        assertEquals("中上江－荆益－江漢", leafKey("中上江－荆益－江漢－漢陽"))
        assertEquals(setOf("中上江－荆益－江漢"), allLeafPaths(tree))
    }

    @Test
    fun leafKeyAndToggleLeavesNormalize() {
        assertEquals("", leafKey("   "))
        assertEquals("湘南", leafKey(" 湘南 "))
        assertEquals("湘贛－岳州", leafKey("湘贛－岳州"))
        assertEquals("湘贛－北湘－長益", leafKey("湘贛－北湘－長益"))
        assertEquals("中上江－荆益－江漢", leafKey("中上江－荆益－江漢－漢陽"))

        val all = setOf("a", "b", "c")
        // 由部分选取补满 → 正规化回 null（全部显示）
        assertNull(toggleLeaves(setOf("b", "c"), setOf("a"), all))
        // 全部已选时取消一个 → 只剩其余叶子
        assertEquals(setOf("b", "c"), toggleLeaves(null, setOf("a"), all))
        // 已选的叶子再点 → 全部取消
        assertEquals(emptySet<String>(), toggleLeaves(setOf("a"), setOf("a"), all))
        // 部分选取时点父节点 → 补齐子树
        assertEquals(setOf("a", "b"), toggleLeaves(setOf("a"), setOf("b"), all))
        // 子树全选时点父节点 → 移除子树
        assertEquals(setOf("c"), toggleLeaves(setOf("a", "b", "c"), setOf("a", "b"), all))
        // 空叶子集合原样返回
        assertEquals(setOf("a"), toggleLeaves(setOf("a"), emptySet(), all))
    }
}
