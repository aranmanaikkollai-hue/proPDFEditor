package com.propdfeditor.viewer

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdfeditor.ui.main.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ViewerFlowTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun homeScreen_showsTitle() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("ProPDF Editor").assertIsDisplayed()
    }

    /** The quick-action row (Files/Scan/Tools/Favorites) exists only when the dashboard has data (Success state). */
    private fun hasQuickActions(): Boolean =
        composeTestRule.onAllNodesWithText("Tools").fetchSemanticsNodes().isNotEmpty()

    @Test
    fun homeScreen_showsQuickActions_orTheEmptyStateActions() {
        composeTestRule.waitForIdle()
        // Always present: the "Open PDF" button (FAB, and in the empty state the primary button too).
        composeTestRule.onAllNodesWithText("Open PDF").onFirst().assertIsDisplayed()
        if (hasQuickActions()) {
            composeTestRule.onNodeWithText("Files").assertIsDisplayed()
            composeTestRule.onNodeWithText("Scan").assertIsDisplayed()
            composeTestRule.onNodeWithText("Tools").assertIsDisplayed()
        } else {
            // Fresh install / no recent files: DashboardEmpty is shown instead.
            composeTestRule.onNodeWithText("Scan Document").assertIsDisplayed()
        }
    }

    @Test
    fun navigateToTools_showsToolGrid() {
        composeTestRule.waitForIdle()
        // The Tools shortcut is part of the populated dashboard only; on an empty dashboard there is no route to it.
        org.junit.Assume.assumeTrue("empty dashboard: no Tools shortcut on this screen", hasQuickActions())
        composeTestRule.onNodeWithText("Tools").performClick()
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Compress").assertIsDisplayed()
        composeTestRule.onNodeWithText("OCR").assertIsDisplayed()
        composeTestRule.onNodeWithText("Merge").assertIsDisplayed()
        composeTestRule.onNodeWithText("Split").assertIsDisplayed()
    }

    @Test
    fun navigateToSettings_showsSettingsList() {
        composeTestRule.waitForIdle()
        // Tap settings icon in top bar
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.waitForIdle()

        // Section headers as they exist in SettingsScreen: Appearance, Storage & Data, Library, Reading, About.
        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Storage & Data").assertIsDisplayed()
    }
}
