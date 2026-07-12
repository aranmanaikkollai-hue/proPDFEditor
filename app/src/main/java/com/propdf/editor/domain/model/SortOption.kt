package com.propdf.editor.domain.model

/**
 * Sort options for document lists in the app-level Document Manager / recent-files
 * feature. This type was referenced (as SortOption.LAST_OPENED) from
 * DocumentUseCases.kt but was never declared anywhere in the codebase - :core has
 * its own com.propdf.core.domain.model.SortOption, but with different values and
 * no LAST_OPENED entry, so it could not have satisfied this reference either.
 * That unresolved reference is a real compile error which the kapt KT-70718 bug
 * swallows during :app's kaptGenerateStubsDebugKotlin task, surfacing only as the
 * generic "Could not load module <Error module>" message.
 */
enum class SortOption {
    NAME,
    LAST_OPENED,
    LAST_MODIFIED,
    SIZE
}
