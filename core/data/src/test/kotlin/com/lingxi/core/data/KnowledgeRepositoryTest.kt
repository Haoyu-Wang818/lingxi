package com.lingxi.core.data

import com.lingxi.core.data.db.NoteEntity
import com.lingxi.core.model.ExtractedPoint
import com.lingxi.core.model.Extraction
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ScoredEdge
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

/** 知识点仓库：图谱查询、归一化去重合并、事务回滚 [FR-KG-02][FR-KG-03]。 */
class KnowledgeRepositoryTest : DbTestBase() {
    private lateinit var repo: RoomKnowledgeRepository
    private var seq = 0

    @Before
    fun setUp() {
        seedGraph()
        repo = RoomKnowledgeRepository(db, clock) { "kp-${++seq}" }
    }

    /** [FR-KG-04] 按课程取图谱与直接前置知识点。 */
    @Test
    fun graphAndPrerequisites() =
        runBlocking {
            val graph = repo.graph("math")
            assertEquals(3, graph.points.size)
            assertEquals(2, graph.edges.size)
            assertEquals(listOf("导数"), repo.prerequisites("taylor").map { it.title })
            assertTrue(repo.prerequisites("derivative").isEmpty())
        }

    /** [FR-KG-02] 归一化名称命中已有知识点（TC-KG-01），新名称新建；来源写入当前笔记。 */
    @Test
    fun mergeDeduplicatesByNormalizedTitle() =
        runBlocking {
            val lesson = repo.createLesson("math", t0)
            db.noteDao().upsert(NoteEntity("n1", lesson, "第5讲", "md", null, "READY", null, t0))
            val extraction =
                Extraction(
                    points =
                        listOf(
                            ExtractedPoint("p1", "泰勒 公式", "重复", listOf("cap-1")),
                            ExtractedPoint("p2", "佩亚诺余项", "局部误差", listOf("cap-2")),
                        ),
                    edges =
                        listOf(
                            ScoredEdge("p2", "p1", RelationType.PART_OF, 0.8),
                            ScoredEdge("p1", "p1", RelationType.RELATED, 0.9),
                            ScoredEdge("p1", "missing", RelationType.RELATED, 0.9),
                        ),
                )
            val result = repo.mergeExtraction(lesson, extraction)
            // kp-1 已用作课次 id，新知识点得到下一个 id。
            assertEquals(mapOf("p1" to "taylor", "p2" to "kp-2"), result.idMap)
            assertEquals(setOf("kp-2"), result.createdIds)
            assertEquals(2, result.rejectedEdges)
            assertEquals(4, repo.graph("math").points.size)
            assertEquals(3, repo.graph("math").edges.size)
        }

    /** [FR-KG-03] 同一条边再次出现时保留置信度高者。 */
    @Test
    fun mergeKeepsHigherConfidenceEdge() =
        runBlocking {
            val lesson = repo.createLesson("math", t0)
            val points = listOf(ExtractedPoint("a", "导数", ""), ExtractedPoint("b", "泰勒公式", ""))
            repo.mergeExtraction(lesson, Extraction(points, listOf(ScoredEdge("a", "b", RelationType.RELATED, 0.7))))
            repo.mergeExtraction(lesson, Extraction(points, listOf(ScoredEdge("a", "b", RelationType.RELATED, 0.4))))
            assertEquals(0.7, db.knowledgeDao().edge("derivative", "taylor", "RELATED")!!.confidence, 1e-9)
            repo.mergeExtraction(lesson, Extraction(points, listOf(ScoredEdge("a", "b", RelationType.RELATED, 0.95))))
            assertEquals(0.95, db.knowledgeDao().edge("derivative", "taylor", "RELATED")!!.confidence, 1e-9)
        }

    /** [FR-KG-02] 软删除的知识点再次被抽取到时恢复，不新建重复节点。 */
    @Test
    fun mergeRevivesSoftDeletedPoint() =
        runBlocking {
            val dao = db.knowledgeDao()
            dao.upsertPoint(dao.byNormTitle("math", "导数")!!.copy(deletedAt = t0))
            assertEquals(2, repo.graph("math").points.size)
            val lesson = repo.createLesson("math", t0)
            val result = repo.mergeExtraction(lesson, Extraction(listOf(ExtractedPoint("x", "导 数", "")), emptyList()))
            assertEquals("derivative", result.idMap["x"])
            assertEquals(3, repo.graph("math").points.size)
        }

    /** TC-KG-06：课次不存在时整体失败，不写入任何知识点。 */
    @Test
    fun mergeFailsAtomically() =
        runBlocking {
            try {
                repo.mergeExtraction("no-such-lesson", Extraction(listOf(ExtractedPoint("p", "新知识点", "")), emptyList()))
                fail("应当抛出异常")
            } catch (expected: IllegalArgumentException) {
                assertTrue(expected.message!!.contains("课次不存在"))
            }
            assertEquals(3, repo.graph("math").points.size)
        }

    /** [FR-KG-02] 归一化：全半角、空白、大小写统一。 */
    @Test
    fun normalizeTitleRules() {
        assertEquals("泰勒展开", normalizeTitle(" 泰勒\t展开 "))
        assertEquals("taylor", normalizeTitle("ＴＡＹＬＯＲ"))
        assertEquals("o(x)", normalizeTitle("ｏ（ｘ）"))
    }
}
