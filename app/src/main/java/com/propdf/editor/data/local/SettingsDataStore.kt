package com.propdf.editor.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // Null means "the user has never explicitly set this" -- callers should fall back
    // to the system theme in that case, matching the app's previous default behavior
    // (isSystemInDarkTheme()) before this preference was wired up to anything.
    val isDarkMode: Flow<Boolean?> = dataStore.data.map { preferences ->
        preferences[Keys.DARK_MODE]
    }

    val isDynamicColor: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.DYNAMIC_COLOR] ?: true
    }

    val isReducedMotion: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.REDUCED_MOTION] ?: false
    }

    // Previously local Compose state in SettingsScreen (`remember { mutableStateOf(false) }`
    // / `remember { mutableStateOf(30f) }`) -- looked like real settings but reset on every
    // recomposition/restart and had no consumer anywhere in the app. Persisting them here
    // fixes the "settings must survive restart" bug. NOTE: compact view still has no layout
    // consumer, and auto-delete-days still has no reader in RecycleBinCleanupWorker/recycle
    // bin insertion -- neither setting drives real app behavior yet. That's a separate,
    // NOT IMPLEMENTED gap; this only fixes persistence of the values themselves.
    val isCompactView: Flow<Boolean> = dataStore.data.map { preferences ->
        preferences[Keys.COMPACT_VIEW] ?: false
    }

    val autoDeleteDays: Flow<Int> = dataStore.data.map { preferences ->
        preferences[Keys.AUTO_DELETE_DAYS] ?: 30
    }

    suspend fun setCompactView(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.COMPACT_VIEW] = enabled
        }
    }

    suspend fun setAutoDeleteDays(days: Int) {
        dataStore.edit { preferences ->
            preferences[Keys.AUTO_DELETE_DAYS] = days
        }
    }

    suspend fun setDarkMode(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.DARK_MODE] = enabled
        }
    }

    suspend fun setDynamicColor(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.DYNAMIC_COLOR] = enabled
        }
    }

    suspend fun setReducedMotion(enabled: Boolean) {
        dataStore.edit { preferences ->
            preferences[Keys.REDUCED_MOTION] = enabled
        }
    }

    private object Keys {
        val DARK_MODE = booleanPreferencesKey("dark_mode")
        val DYNAMIC_COLOR = booleanPreferencesKey("dynamic_color")
        val REDUCED_MOTION = booleanPreferencesKey("reduced_motion")
        val COMPACT_VIEW = booleanPreferencesKey("compact_view")
        val AUTO_DELETE_DAYS = intPreferencesKey("auto_delete_days")
    }
}
