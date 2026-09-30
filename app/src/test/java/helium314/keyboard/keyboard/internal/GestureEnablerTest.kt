// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.keyboard.internal

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import android.view.accessibility.AccessibilityManager
import helium314.keyboard.latin.App
import helium314.keyboard.accessibility.AccessibilityUtils
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
class GestureEnablerTest {
    @Before fun initializeAccessibility() {
        AccessibilityUtils.init(ApplicationProvider.getApplicationContext<App>())
    }

    @Test fun supportedFutoStrokeDoesNotWaitForTheOtherDictionary() {
        ApplicationProvider.getApplicationContext<App>()
        val gate = GestureEnabler()
        gate.setPasswordMode(false)
        gate.setGestureHandlingEnabledByUser(true)
        gate.setFutoGestureAvailability(true)
        gate.setMainDictionaryAvailability(false)
        assertTrue(gate.shouldHandleGesture())
        gate.setMainDictionaryAvailability(true)
        gate.setMainDictionaryAvailability(false)
        assertTrue(gate.shouldHandleGesture())
        gate.setFutoGestureAvailability(false)
        assertFalse(gate.shouldHandleGesture())
    }

    @Test fun futoKeepsTheUserAndPasswordGates() {
        ApplicationProvider.getApplicationContext<App>()
        val gate = GestureEnabler()
        gate.setFutoGestureAvailability(true)
        gate.setMainDictionaryAvailability(true)
        gate.setPasswordMode(false)
        gate.setGestureHandlingEnabledByUser(false)
        assertFalse(gate.shouldHandleGesture())
        gate.setGestureHandlingEnabledByUser(true)
        assertTrue(gate.shouldHandleGesture())
        gate.setPasswordMode(true)
        assertFalse(gate.shouldHandleGesture())
        gate.setPasswordMode(false)
        gate.setMainDictionaryAvailability(false)
        gate.setFutoGestureAvailability(false)
        assertFalse(gate.shouldHandleGesture())
    }

    @Test fun touchExplorationStillDisablesSwipes() {
        val context = ApplicationProvider.getApplicationContext<App>()
        val accessibility = Shadows.shadowOf(context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager)
        accessibility.setEnabled(true)
        accessibility.setTouchExplorationEnabled(true)
        val gate = GestureEnabler()
        gate.setPasswordMode(false)
        gate.setGestureHandlingEnabledByUser(true)
        gate.setFutoGestureAvailability(true)
        assertFalse(gate.shouldHandleGesture())
    }
}
