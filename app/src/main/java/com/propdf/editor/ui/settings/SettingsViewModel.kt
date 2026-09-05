package com.propdf.editor.ui.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.propdf.editor.data.local.SettingsDataStore
import com.propdf.editor.domain.usecase.EmptyRecycleBinUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class SettingsActionResult(val message: String)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val emptyRecycleBinUseCase: EmptyRecycleBinUseCase,
    private val settingsDataStore: SettingsDataStore
) : ViewModel() {

    private val _actionResult = MutableStateFlow<SettingsActionResult?>(null)
    val actionResult: StateFlow<SettingsActionResult?> = _actionResult.asStateFlow()

    // Previously Dark Mode / Dynamic Colors were local `remember { mutableStateOf(...) }`
    // in SettingsScreen -- they looked like real settings but reset on every recomposition
    // and never reached MainActivity's ProPDFTheme(...) call, which always used its
    // defaults (isSystemInDarkTheme(), dynamicColor = true) regardless of what the user
    // picked. SettingsDataStore already existed with the right shape for this but had
    // zero consumers anywhere. Wiring it here makes the toggle persist and actually
    // apply, via MainActivity reading the same flows.
    // Resolved the same way MainActivity resolves it: null (never explicitly set)
    // falls back to whatever the system theme currently is, so the switch reflects
    // the app's actual current appearance rather than defaulting to "off".
    val isDarkMode: StateFlow<Boolean> = settingsDataStore.isDarkMode
        .map { explicit ->
            explicit ?: run {
                val nightMode = context.resources.configuration.uiMode and
                    android.content.res.Configuration.UI_MODE_NIGHT_MASK
                nightMode == android.content.res.Configuration.UI_MODE_NIGHT_YES
            }
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val isDynamicColor: StateFlow<Boolean> = settingsDataStore.isDynamicColor
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    fun setDarkMode(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDarkMode(enabled) }
    }

    fun setDynamicColor(enabled: Boolean) {
        viewModelScope.launch { settingsDataStore.setDynamicColor(enabled) }
    }

    fun consumeActionResult() {
        _actionResult.value = null
    }

    /**
     * Immediately clears the app's cache directory. This is a deliberate full
     * clear-now, distinct from CleanupWorker's periodic age-based trim (which
     * only removes files older than a few days) — a user tapping "Clear Cache"
     * expects space back immediately, not a partial age-based sweep.
     */
    fun clearCache() {
        viewModelScope.launch {
            val freedBytes = withContext(Dispatchers.IO) {
                var freed = 0L
                try {
                    context.cacheDir?.listFiles()?.forEach { file ->
                        freed += dirSize(file)
                        file.deleteRecursively()
                    }
                } catch (_: Exception) {
                }
                // Also clean up the pre-migration annotation cache directory, if
                // any files are still left over from before annotations moved to
                // Room-backed storage.
                try {
                    val legacyAnnotationCache = File(context.filesDir, "annotation_cache")
                    if (legacyAnnotationCache.exists()) {
                        freed += dirSize(legacyAnnotationCache)
                        legacyAnnotationCache.deleteRecursively()
                    }
                } catch (_: Exception) {
                }
                freed
            }
            val freedMb = freedBytes / (1024f * 1024f)
            _actionResult.value = SettingsActionResult(
                if (freedBytes > 0) "Cleared %.1f MB".format(freedMb) else "Cache already empty"
            )
        }
    }

    private fun dirSize(file: File): Long =
        if (file.isDirectory) file.listFiles()?.sumOf { dirSize(it) } ?: 0L else file.length()

    /**
     * Permanently deletes everything already sitting in the recycle bin, via the
     * same EmptyRecycleBinUseCase the (separately orphaned) RecycleBinScreen
     * already had wired — that use case was real, just never called from
     * anywhere reachable.
     */
    fun emptyRecycleBin() {
        viewModelScope.launch {
            try {
                // olderThanDays = 0: the user explicitly asked to empty it now,
                // not to run the same age-based trim the recycle bin already does
                // automatically.
                emptyRecycleBinUseCase(olderThanDays = 0)
                _actionResult.value = SettingsActionResult("Recycle bin emptied")
            } catch (e: Exception) {
                _actionResult.value = SettingsActionResult("Couldn't empty recycle bin: ${e.message}")
            }
        }
    }
}
