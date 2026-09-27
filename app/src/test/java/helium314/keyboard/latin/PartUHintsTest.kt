// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin

import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import helium314.keyboard.keyboard.internal.keyboard_parser.floris.KeyCode
import helium314.keyboard.latin.utils.ToolbarKey
import helium314.keyboard.latin.utils.clipboardEmptyRetentionText
import helium314.keyboard.latin.utils.clipboardRetentionLabel
import helium314.keyboard.latin.utils.defaultCodeForToolbarKeyLongClick
import helium314.keyboard.latin.utils.toolbarActionName
import helium314.keyboard.latin.utils.toolbarKeyForCode
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
class PartUHintsTest {
    private val context = ApplicationProvider.getApplicationContext<App>()

    @Test fun functionalKeyHintsNameTheRealHoldAndLeaveCharacterPopupsAlone() {
        val keyboard = org.mockito.Mockito.mock(helium314.keyboard.keyboard.Keyboard::class.java)
        val key = org.mockito.Mockito.mock(helium314.keyboard.keyboard.Key::class.java)
        org.mockito.Mockito.doReturn(KeyCode.SHIFT).`when`(key).code
        org.mockito.Mockito.doReturn(true).`when`(key).hasNoPanelAutoPopupKey()
        org.mockito.Mockito.doReturn(arrayOf(helium314.keyboard.keyboard.internal.PopupKeySpec(" |!code/key_capslock", false, java.util.Locale.ENGLISH))).`when`(key).popupKeys
        assertEquals(context.getString(R.string.button_hold_hint, "Shift", "Caps lock"), helium314.keyboard.latin.utils.keyboardKeyHint(context, keyboard, key, true))
        org.mockito.Mockito.doReturn(KeyCode.DELETE).`when`(key).code
        org.mockito.Mockito.doReturn(true).`when`(key).isRepeatable
        assertEquals(context.getString(R.string.button_hold_hint, "Delete", context.getString(R.string.button_hold_repeat)), helium314.keyboard.latin.utils.keyboardKeyHint(context, keyboard, key, true))
        org.mockito.Mockito.doReturn(false).`when`(key).isRepeatable
        assertEquals("Delete", helium314.keyboard.latin.utils.keyboardKeyHint(context, keyboard, key, false))
        org.mockito.Mockito.doReturn(97).`when`(key).code
        assertNull(helium314.keyboard.latin.utils.keyboardKeyHint(context, keyboard, key, true))
    }

    @Test fun hiddenLongPressActionsMapToTheirOwnKeys() {
        assertEquals(ToolbarKey.CUT, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.COPY)))
        assertEquals(ToolbarKey.PASTE, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.CLIPBOARD)))
        // CLOSE_HISTORY shares the CLIPBOARD code; the name must stay "Clipboard"
        assertEquals(ToolbarKey.CLIPBOARD, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.PASTE)))
        assertEquals(ToolbarKey.REDO, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.UNDO)))
        assertEquals(ToolbarKey.UNDO, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.REDO)))
        assertEquals(ToolbarKey.SELECT_WORD, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.SELECT_ALL)))
        assertEquals(ToolbarKey.SELECT_ALL, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.SELECT_WORD)))
        assertEquals(ToolbarKey.PAGE_START, toolbarKeyForCode(defaultCodeForToolbarKeyLongClick(ToolbarKey.PAGE_UP)))
        assertNull(toolbarKeyForCode(KeyCode.KEY_REPEAT))
    }

    @Test fun heldCopyIsNamedCut() {
        assertEquals(context.getString(android.R.string.cut),
            toolbarActionName(context, defaultCodeForToolbarKeyLongClick(ToolbarKey.COPY)))
        assertEquals(context.getString(android.R.string.paste),
            toolbarActionName(context, defaultCodeForToolbarKeyLongClick(ToolbarKey.CLIPBOARD)))
        assertEquals("Redo", toolbarActionName(context, defaultCodeForToolbarKeyLongClick(ToolbarKey.UNDO)))
    }

    @Test fun permissionAnswerIsClassified() {
        val granted = intArrayOf(PackageManager.PERMISSION_GRANTED)
        val denied = intArrayOf(PackageManager.PERMISSION_DENIED)
        assertEquals(VoicePermissionActivity.GRANTED, VoicePermissionActivity.classify(granted, false))
        assertEquals(VoicePermissionActivity.DENIED, VoicePermissionActivity.classify(denied, true))
        assertEquals(VoicePermissionActivity.DENIED_PERMANENTLY, VoicePermissionActivity.classify(denied, false))
        assertEquals(VoicePermissionActivity.DENIED, VoicePermissionActivity.classify(intArrayOf(), false))
    }

    @Test fun retentionTextNamesTheSettingValue() {
        assertEquals("10 minutes", clipboardRetentionLabel(context, 10))
        assertEquals("1 hour", clipboardRetentionLabel(context, 60))
        assertEquals("45 minutes", clipboardRetentionLabel(context, 45))
        assertEquals("Copies are kept for 1 hour. You can change this in settings.",
            clipboardEmptyRetentionText(context, 60))
        assertEquals("Copies are kept until you delete them.", clipboardEmptyRetentionText(context, -1))
    }
}
