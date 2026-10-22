package dev.muhib.s23optimizer.ui

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.muhib.s23optimizer.MainActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Launches the real activity on Robolectric with no Shizuku installed and walks the main screens. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AppSmokeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private fun tab(label: String) = compose.onNodeWithText(label).performClick()

    @Test fun `first run without shizuku shows onboarding and blocks every change`() {
        compose.onNodeWithText("Understand first. Change deliberately.").assertExists()
        compose.onNodeWithText("Shizuku · not installed").performScrollTo().assertExists()
        compose.onNodeWithText("Get Shizuku").performScrollTo().assertExists()

        compose.onNodeWithText("Continue to diagnostics").performScrollTo().performClick()
        compose.onNodeWithText("Your phone, measured").assertExists()
        compose.onNodeWithText("Save BEFORE audit").assertExists()

        tab("Optimise")
        compose.onNodeWithText("Changes are disabled until Shizuku is ready. Nothing will run, and nothing is journaled.").assertExists()
        compose.onNodeWithText("Review animation changes").performScrollTo().assertIsNotEnabled()

        tab("History")
        compose.onNodeWithText("Restore defaults").assertIsNotEnabled()
        compose.onNodeWithText("Nothing to restore: no pending changes.").assertExists()
        compose.onNodeWithText("Preview restore commands").assertIsNotEnabled()

        tab("Measured")
        compose.onNodeWithText("No tweak is applied yet, so every measurement counts as the baseline (Before).").assertExists()
        assertTrue(compose.onAllNodesWithText("Before: not enough data").fetchSemanticsNodes().size == 2)
        compose.onNodeWithText("Export CSV").performScrollTo().assertExists()
    }
}
