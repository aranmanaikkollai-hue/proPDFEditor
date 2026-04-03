// FILE: CategoryManager.kt
// FLAT REPO ROOT -- codemagic.yaml copies to:
// app/src/main/java/com/propdf/editor/utils/CategoryManager.kt
//
// FEATURE: Category sub-categories (nested with "/" separator)
//   - "Work/Invoices" means parent="Work", child="Invoices"
//   - Unlimited depth: "Work/Invoices/2025"
//   - Fixed bugs:
//       * Categories disappearing after creation
//       * Cannot create empty (file-less) category
//   - Storage: SharedPreferences StringSet "user_categories" (matches existing code)
//   - Room category column on RecentFileEntity holds full path e.g. "Work/Invoices"
//
// RULES OBEYED:
//   - Pure ASCII  (rule #32)
//   - No JitPack

package com.propdf.editor.utils

import android.content.Context
import android.content.SharedPreferences

object CategoryManager {

    private const val PREFS_NAME       = "propdf_prefs"
    private const val KEY_USER_CATS    = "user_categories"

    // -----------------------------------------------------------------------
    // READ
    // -----------------------------------------------------------------------

    // All category paths, sorted.  e.g. ["General", "Work", "Work/Invoices"]
    fun getAllCategories(context: Context): List<String> {
        val prefs = prefs(context)
        val saved = prefs.getStringSet(KEY_USER_CATS, emptySet()) ?: emptySet()
        return saved.filter { it.isNotBlank() }.sorted()
    }

    // Root categories only (no "/" in path)
    fun getRootCategories(context: Context): List<String> {
        return getAllCategories(context).filter { !it.contains("/") }
    }

    // Direct children of a parent path.
    // e.g. getChildren("Work") -> ["Invoices", "Reports"]  (not grandchildren)
    fun getChildren(context: Context, parentPath: String): List<String> {
        val prefix = "$parentPath/"
        return getAllCategories(context)
            .filter { it.startsWith(prefix) }
            .map { it.removePrefix(prefix) }
            .filter { !it.contains("/") }   // direct children only
    }

    // Check if a category has any sub-categories
    fun hasChildren(context: Context, path: String): Boolean {
        return getAllCategories(context).any { it.startsWith("$path/") }
    }

    // -----------------------------------------------------------------------
    // CREATE / DELETE / RENAME
    // -----------------------------------------------------------------------

    // Create a category.  parentPath = "" for root level.
    // Returns the full path of the created category.
    fun createCategory(
        context: Context,
        name: String,
        parentPath: String = ""
    ): String {
        val cleanName = name.trim().replace("/", "-")  // slash is separator
        if (cleanName.isBlank()) return ""
        val fullPath = if (parentPath.isBlank()) cleanName else "$parentPath/$cleanName"

        val prefs   = prefs(context)
        val current = (prefs.getStringSet(KEY_USER_CATS, emptySet()) ?: emptySet()).toMutableSet()

        if (!current.contains(fullPath)) {
            current.add(fullPath)
            // Ensure all ancestor paths also exist
            ensureAncestors(context, fullPath, current)
            // FIX: putStringSet then commit (not apply) so it persists immediately
            prefs.edit().putStringSet(KEY_USER_CATS, current).commit()
        }
        return fullPath
    }

    // Delete a category and all its descendants.
    // Note: files in the deleted category are moved to "General" in the Room DB --
    // caller must also call dao.reassignCategory(deletedPath, "General").
    fun deleteCategory(context: Context, fullPath: String) {
        val prefs   = prefs(context)
        val current = (prefs.getStringSet(KEY_USER_CATS, emptySet()) ?: emptySet()).toMutableSet()
        // Remove exact path AND all descendants (paths starting with "fullPath/")
        current.removeAll { it == fullPath || it.startsWith("$fullPath/") }
        prefs.edit().putStringSet(KEY_USER_CATS, current).commit()
    }

    // Rename: updates the path and all descendant paths.
    // Returns the new full path.
    fun renameCategory(context: Context, oldPath: String, newName: String): String {
        val cleanName = newName.trim().replace("/", "-")
        if (cleanName.isBlank()) return oldPath

        val parentPath = parentOf(oldPath)
        val newPath    = if (parentPath.isBlank()) cleanName else "$parentPath/$cleanName"

        val prefs   = prefs(context)
        val current = (prefs.getStringSet(KEY_USER_CATS, emptySet()) ?: emptySet()).toMutableSet()

        // Replace old path and all descendant paths
        val updated = current.map { cat ->
            when {
                cat == oldPath              -> newPath
                cat.startsWith("$oldPath/") -> newPath + cat.removePrefix(oldPath)
                else                         -> cat
            }
        }.toMutableSet()

        prefs.edit().putStringSet(KEY_USER_CATS, updated).commit()
        return newPath
    }

    // Move a category under a new parent.
    fun moveCategory(context: Context, fullPath: String, newParentPath: String): String {
        val leafName = leafOf(fullPath)
        val newPath  = if (newParentPath.isBlank()) leafName else "$newParentPath/$leafName"

        val prefs   = prefs(context)
        val current = (prefs.getStringSet(KEY_USER_CATS, emptySet()) ?: emptySet()).toMutableSet()

        val updated = current.map { cat ->
            when {
                cat == fullPath              -> newPath
                cat.startsWith("$fullPath/") -> newPath + cat.removePrefix(fullPath)
                else                          -> cat
            }
        }.toMutableSet()

        prefs.edit().putStringSet(KEY_USER_CATS, updated).commit()
        return newPath
    }

    // -----------------------------------------------------------------------
    // DISPLAY HELPERS
    // -----------------------------------------------------------------------

    // Indent display name based on depth
    fun displayName(fullPath: String): String {
        val depth  = fullPath.count { it == '/' }
        val indent = "   ".repeat(depth)
        return "$indent${leafOf(fullPath)}"
    }

    // Get the leaf (last) segment of a path
    fun leafOf(path: String): String = path.substringAfterLast("/", path)

    // Get the parent path ("" if root)
    fun parentOf(path: String): String {
        val idx = path.lastIndexOf("/")
        return if (idx < 0) "" else path.substring(0, idx)
    }

    // Build a breadcrumb string: "Work > Invoices > 2025"
    fun breadcrumb(fullPath: String): String = fullPath.replace("/", " > ")

    // -----------------------------------------------------------------------
    // INTERNAL
    // -----------------------------------------------------------------------

    // Ensure all ancestor paths are present (so "Work/Invoices" implies "Work" exists)
    private fun ensureAncestors(context: Context, path: String, set: MutableSet<String>) {
        var current = path
        while (current.contains("/")) {
            current = parentOf(current)
            if (current.isNotBlank() && !set.contains(current)) {
                set.add(current)
            }
        }
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
