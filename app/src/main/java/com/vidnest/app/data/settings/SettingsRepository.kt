package com.vidnest.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.vidnest.app.domain.model.EndBehavior
import com.vidnest.app.domain.model.Speeds
import com.vidnest.app.domain.model.ResumeBehavior
import com.vidnest.app.domain.model.VideoSortField
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "vidnest_settings")

/** 本地设置（docs/requirements.md §1.8），全部离线存 DataStore */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val DEFAULT_SORT = stringPreferencesKey("default_sort")
        val RESUME_BEHAVIOR = stringPreferencesKey("resume_behavior")
        val END_BEHAVIOR = stringPreferencesKey("end_behavior")
        val REMEMBER_SPEED = booleanPreferencesKey("remember_speed")
        val SPEED_OPTIONS = stringPreferencesKey("speed_options")
        val PLAYBACK_SPEED = floatPreferencesKey("playback_speed")
        val SAF_TREES = stringSetPreferencesKey("saf_trees")
    }

    val defaultSort: Flow<VideoSortField> = context.dataStore.data.map { prefs ->
        prefs[Keys.DEFAULT_SORT]?.let { stored ->
            VideoSortField.entries.firstOrNull { it.name == stored }
        } ?: VideoSortField.NAME
    }

    val resumeBehavior: Flow<ResumeBehavior> = context.dataStore.data.map { prefs ->
        prefs[Keys.RESUME_BEHAVIOR]?.let { stored ->
            ResumeBehavior.entries.firstOrNull { it.name == stored }
        } ?: ResumeBehavior.ALWAYS
    }

    val endBehavior: Flow<EndBehavior> = context.dataStore.data.map { prefs ->
        prefs[Keys.END_BEHAVIOR]?.let { stored ->
            EndBehavior.entries.firstOrNull { it.name == stored }
        } ?: EndBehavior.FROM_START
    }

    val rememberSpeed: Flow<Boolean> = context.dataStore.data.map { it[Keys.REMEMBER_SPEED] ?: true }

    /** 用户自定义的倍速列表（升序），默认 1.0/1.5/2.0/2.5/3.0 */
    val speedOptions: Flow<List<Float>> = context.dataStore.data.map { prefs ->
        Speeds.decode(prefs[Keys.SPEED_OPTIONS])
    }

    val playbackSpeed: Flow<Float> = context.dataStore.data.map { it[Keys.PLAYBACK_SPEED] ?: 1.0f }

    /** 已授权的 SAF 目录 tree URI 集合 */
    val safTrees: Flow<Set<String>> = context.dataStore.data.map { it[Keys.SAF_TREES] ?: emptySet() }

    suspend fun setDefaultSort(value: VideoSortField) {
        context.dataStore.edit { it[Keys.DEFAULT_SORT] = value.name }
    }

    suspend fun setResumeBehavior(value: ResumeBehavior) {
        context.dataStore.edit { it[Keys.RESUME_BEHAVIOR] = value.name }
    }

    suspend fun setEndBehavior(value: EndBehavior) {
        context.dataStore.edit { it[Keys.END_BEHAVIOR] = value.name }
    }

    suspend fun setRememberSpeed(value: Boolean) {
        context.dataStore.edit { it[Keys.REMEMBER_SPEED] = value }
    }

    suspend fun setSpeedOptions(options: List<Float>) {
        context.dataStore.edit { it[Keys.SPEED_OPTIONS] = Speeds.encode(options) }
    }

    suspend fun setPlaybackSpeed(value: Float) {
        context.dataStore.edit { it[Keys.PLAYBACK_SPEED] = value }
    }

    suspend fun addSafTree(treeUri: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SAF_TREES] = (prefs[Keys.SAF_TREES] ?: emptySet()) + treeUri
        }
    }

    suspend fun removeSafTree(treeUri: String) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SAF_TREES] = (prefs[Keys.SAF_TREES] ?: emptySet()) - treeUri
        }
    }
}
