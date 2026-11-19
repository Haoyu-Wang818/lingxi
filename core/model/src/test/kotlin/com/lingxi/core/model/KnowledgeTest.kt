package com.lingxi.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class KnowledgeTest {
    @Test
    @DisplayName("知识点名称为空时拒绝创建")
    fun rejectsBlankTitle() {
        assertThrows<IllegalArgumentException> {
            KnowledgePoint(id = "k1", title = " ", summary = "", courseId = "math")
        }
    }

    @Test
    @DisplayName("知识点不能指向自身")
    fun rejectsSelfLoop() {
        assertThrows<IllegalArgumentException> {
            KnowledgeEdge("k1", "k1", RelationType.RELATED)
        }
    }

    @Test
    @DisplayName("评分与 FSRS 分值一致")
    fun ratingsMatchFsrsGrades() {
        assertEquals(listOf(1, 2, 3, 4), Rating.entries.map { it.grade })
    }

    @Test
    @DisplayName("录音时长必须为正")
    fun audioDurationMustBePositive() {
        assertThrows<IllegalArgumentException> { CaptureSource.Audio("file://a.m4a", durationMs = 0) }
    }
}
