package com.propdfeditor.ui.home

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.propdfeditor.ui.home.components.DashboardEmpty
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Phase 1 closure: empty-dashboard actions in isolation (no Hilt, no device state dependence). */
@RunWith(AndroidJUnit4::class)
class DashboardEmptyTest {

    @get:Rule val rule = createComposeRule()

    private var open = 0; private var scan = 0; private var tools = 0

    private fun show() = rule.setContent {
        DashboardEmpty(onOpenFile = { open++ }, onScanDocument = { scan++ }, onExploreTools = { tools++ })
    }

    @Test fun allThreeActionsAreVisibleAndClickable() {
        show()
        for (label in listOf("Open PDF", "Scan Document", "Explore Tools")) {
            rule.onNodeWithText(label).assertIsDisplayed()
            rule.onNodeWithText(label).assertHasClickAction()
        }
    }

    @Test fun exploreTools_invokesOnlyItsCallback() {
        show()
        rule.onNodeWithText("Explore Tools").performClick()
        assertEquals(1, tools); assertEquals(0, open); assertEquals(0, scan)
    }

    @Test fun existingActions_stillInvokeTheirCallbacks() {
        show()
        rule.onNodeWithText("Open PDF").performClick()
        rule.onNodeWithText("Scan Document").performClick()
        assertEquals(1, open); assertEquals(1, scan); assertEquals(0, tools)
    }
}
