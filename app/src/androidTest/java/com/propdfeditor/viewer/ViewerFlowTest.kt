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

    @Test
    fun homeScreen_showsQuickActions() {
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("Files").assertIsDisplayed()
        composeTestRule.onNodeWithText("Scan").assertIsDisplayed()
        composeTestRule.onNodeWithText("Tools").assertIsDisplayed()
    }

    @Test
    fun navigateToTools_showsToolGrid() {
        composeTestRule.waitForIdle()
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

        composeTestRule.onNodeWithText("Appearance").assertIsDisplayed()
        composeTestRule.onNodeWithText("Viewer").assertIsDisplayed()
        composeTestRule.onNodeWithText("Storage").assertIsDisplayed()
    }
}
