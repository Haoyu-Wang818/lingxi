package com.lingxi.app.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lingxi.core.ai.PrivacySettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

private val Context.settingsStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 设置项，按 03-database.md 第 5 节保存在 DataStore（不进数据库）[FR-SET-01]。
 */
class AppSettings(
    context: Context,
) {
    private val store = context.applicationContext.settingsStore

    private object Keys {
        val uploadOcrText = booleanPreferencesKey("privacy.uploadOcrText")
        val uploadTranscript = booleanPreferencesKey("privacy.uploadTranscript")
        val semesterStart = stringPreferencesKey("course.semesterStart")
        val deviceId = stringPreferencesKey("device.id")
        val seeded = booleanPreferencesKey("seed.done")
    }

    /** 首次启动的示例数据只写一次；用户删除示例课程后不会再次出现。 */
    suspend fun isSeeded(): Boolean = store.data.first()[Keys.seeded] ?: false

    suspend fun markSeeded() {
        store.edit { it[Keys.seeded] = true }
    }

    /** 隐私设置，默认都允许上传文本（音频永远不上传）。 */
    val privacy: Flow<PrivacySettings> =
        store.data.map { p -> PrivacySettings(p[Keys.uploadOcrText] ?: true, p[Keys.uploadTranscript] ?: true) }

    suspend fun setPrivacy(settings: PrivacySettings) {
        store.edit {
            it[Keys.uploadOcrText] = settings.uploadOcrText
            it[Keys.uploadTranscript] = settings.uploadTranscript
        }
    }

    /** 学期第一天；未设置时按今天推算（秋季学期 9 月第一个周一，春季学期 2 月最后一个周一）。 */
    val semesterStart: Flow<LocalDate> =
        store.data.map { p -> p[Keys.semesterStart]?.let(LocalDate::parse) ?: defaultSemesterStart(LocalDate.now()) }

    suspend fun setSemesterStart(date: LocalDate) {
        store.edit { it[Keys.semesterStart] = date.toString() }
    }

    /** 本机设备 id，首次读取时生成；网关按它限流（FR-GW-05）。 */
    suspend fun deviceId(): String {
        store.data.first()[Keys.deviceId]?.let { return it }
        val id = UUID.randomUUID().toString()
        store.edit { it[Keys.deviceId] = id }
        return id
    }

    companion object {
        fun defaultSemesterStart(today: LocalDate): LocalDate {
            val autumn = LocalDate.of(today.year, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))
            val spring = LocalDate.of(today.year, 2, 1).with(TemporalAdjusters.lastInMonth(DayOfWeek.MONDAY))
            return when {
                !today.isBefore(autumn) -> autumn
                !today.isBefore(spring) -> spring
                else -> LocalDate.of(today.year - 1, 9, 1).with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY))
            }
        }
    }
}
