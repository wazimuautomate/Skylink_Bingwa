package com.example.feature.onboarding

import android.provider.Settings
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.example.ui.theme.SkylinkBingwaTheme
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Compose behaviour for the onboarding permission steps, which are **required**:
 * they come after the name and number, cannot be skipped, and the only
 * alternative to granting them is closing the app.
 *
 * Two deliberate testing choices:
 *
 * - `@Config(sdk = [30])`: onboarding's owner-approved glass look uses
 *   `Modifier.blur`, which only creates a `RenderEffect` on API 31+. Running the
 *   screen at API 30 makes the blur a documented no-op and keeps the test off
 *   Robolectric's render-effect path; nothing else on these steps is API-gated.
 * - `mainClock.autoAdvance = false` plus a zero animator duration scale: the
 *   welcome screens run ambient infinite animations, which would otherwise mean
 *   the tree is never "idle". Time is advanced explicitly after each tap so the
 *   step transition settles.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class OnboardingPermissionComposeTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Before
    fun disableSystemAnimations() {
        // Best-effort: makes the screen take its reduced-motion path. The manual
        // clock below is what actually guarantees the test never waits on an
        // infinite animation, so a shadow that rejects this write is harmless.
        runCatching {
            Settings.Global.putFloat(
                RuntimeEnvironment.getApplication().contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                0f
            )
        }
        composeRule.mainClock.autoAdvance = false
    }

    private fun settle() {
        composeRule.mainClock.advanceTimeBy(1_000L)
    }

    private fun tapCta() {
        composeRule.onNodeWithTag("onboarding_primary_cta").performClick()
        settle()
    }

    private fun fillSetup(name: String = "Asha", phone: String = "0712345678") {
        composeRule.onNodeWithText("Your name").performTextInput(name)
        composeRule.onNodeWithText("Safaricom number").performTextInput(phone)
        settle()
    }

    @Test
    fun `the personal setup comes before the permissions`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(onCompleteOnboarding = { _, _ -> })
            }
        }
        tapCta() // welcome → setup
        composeRule.onNodeWithTag("onboarding_step_setup", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `the setup step will not advance without a name and number`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(onCompleteOnboarding = { _, _ -> })
            }
        }
        tapCta() // welcome → setup
        // Tapping Continue with empty fields keeps the customer on the setup step.
        tapCta()
        composeRule.onNodeWithTag("onboarding_step_setup", useUnmergedTree = true).assertExists()
        composeRule.onNodeWithTag("onboarding_step_notifications", useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a refused notification permission cannot be skipped past`() {
        var notificationRequests = 0
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(
                    onCompleteOnboarding = { _, _ -> },
                    onRequestNotificationPermission = { notificationRequests++ },
                    // notificationsGranted stays false: the customer refuses.
                    notificationsGranted = false
                )
            }
        }
        tapCta() // welcome → setup
        fillSetup()
        tapCta() // setup → notifications
        composeRule.onNodeWithTag("onboarding_step_notifications", useUnmergedTree = true)
            .assertExists()

        // Ask, refuse, ask again — and never leave the step.
        tapCta()
        assertEquals(1, notificationRequests)
        composeRule.onNodeWithTag("onboarding_step_notifications", useUnmergedTree = true)
            .assertExists()

        tapCta()
        assertEquals(2, notificationRequests)
        composeRule.onNodeWithTag("onboarding_step_notifications", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `after two refusals the cta opens the app settings instead of asking again`() {
        var notificationRequests = 0
        var settingsOpened = 0
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(
                    onCompleteOnboarding = { _, _ -> },
                    onRequestNotificationPermission = { notificationRequests++ },
                    onOpenAppSettings = { settingsOpened++ }
                )
            }
        }
        tapCta() // welcome → setup
        fillSetup()
        tapCta() // setup → notifications

        tapCta() // ask 1
        tapCta() // ask 2
        assertEquals(2, notificationRequests)

        // Android no longer shows its dialog, so the third tap must route to the
        // one place the permission can still be granted.
        tapCta()
        assertEquals(2, notificationRequests)
        assertEquals(1, settingsOpened)
    }

    @Test
    fun `refusing offers closing the app, not skipping the step`() {
        var exits = 0
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(
                    onCompleteOnboarding = { _, _ -> },
                    onExitApp = { exits++ }
                )
            }
        }
        tapCta() // welcome → setup
        fillSetup()
        tapCta() // setup → notifications

        composeRule.onNodeWithTag("onboarding_permission_exit").performClick()
        settle()
        assertEquals(1, exits)
        // The step is still the one it was: exiting is the caller's job, not a skip.
        composeRule.onNodeWithTag("onboarding_step_notifications", useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `granting the notification permission completes onboarding with the entered details`() {
        var completedName: String? = null
        var completedPhone: String? = null
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(
                    onCompleteOnboarding = { name, phone ->
                        completedName = name
                        completedPhone = phone
                    },
                    notificationsGranted = true
                )
            }
        }
        tapCta() // welcome → setup
        fillSetup(name = "Asha", phone = "0712345678")
        tapCta() // setup → notifications, already satisfied → finish
        composeRule.mainClock.advanceTimeBy(2_000L)

        assertEquals("Asha", completedName)
        assertEquals("0712345678", completedPhone)
    }

    @Test
    fun `there is no skip shortcut anywhere in the flow`() {
        composeRule.setContent {
            SkylinkBingwaTheme {
                OnboardingScreen(onCompleteOnboarding = { _, _ -> })
            }
        }
        composeRule.onNodeWithTag("onboarding_skip_to_setup").assertDoesNotExist()
        tapCta() // welcome → setup
        fillSetup()
        tapCta()
        composeRule.onNodeWithTag("onboarding_permission_skip").assertDoesNotExist()
    }
}
