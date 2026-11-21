package com.lingxi.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.lingxi.core.data.db.LingxiDatabase
import com.lingxi.core.model.CardType
import com.lingxi.core.model.Course
import com.lingxi.core.model.KnowledgeEdge
import com.lingxi.core.model.KnowledgePoint
import com.lingxi.core.model.RelationType
import com.lingxi.core.model.ReviewCard
import com.lingxi.core.model.SourceRef
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 每个测试使用一个全新的内存数据库，外键约束与真机一致。 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
abstract class DbTestBase {
    protected lateinit var db: LingxiDatabase
    protected val t0 = 1_795_000_000_000L
    protected var now = t0
    protected val clock = { now }

    @Before
    fun openDb() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(context, LingxiDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }

    @After
    fun closeDb() = db.close()

    protected val math = Course("math", "高等数学", "王老师", createdAtMillis = 1)
    protected val points =
        listOf(
            KnowledgePoint("derivative", "导数", "瞬时变化率", "math"),
            KnowledgePoint("taylor", "泰勒公式", "多项式逼近", "math"),
            KnowledgePoint("maclaurin", "麦克劳林公式", "x₀ = 0 的泰勒公式", "math"),
        )
    protected val edges =
        listOf(
            KnowledgeEdge("derivative", "taylor", RelationType.PREREQUISITE),
            KnowledgeEdge("maclaurin", "taylor", RelationType.EXAMPLE_OF),
        )

    protected fun card(
        id: String,
        kp: String = "taylor",
    ) = ReviewCard(
        id = id,
        knowledgePointId = kp,
        question = "问题$id",
        answer = "答案$id",
        type = CardType.SINGLE_CHOICE,
        options = listOf("甲", "乙"),
        correctIndex = 1,
        watchPrompt = "手表$id",
        watchAnswer = "乙",
        sourceRef = SourceRef("cap-1", page = 3, box = listOf(1, 2, 3, 4)),
    )

    /** 写入一门课、三个知识点和关系。 */
    protected fun seedGraph() =
        runBlocking {
            RoomCourseRepository(db, clock).saveCourse(math)
            RoomKnowledgeRepository(db, clock).upsert(points, edges)
        }
}
