package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.ArrayList;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DeferredVoiceDeliveryTest {
    private static final long SESSION = 77;
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int PASSWORD = TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD;
    private Context context;

    @Before public void clearRecovery() {
        context = RuntimeEnvironment.getApplication();
        File base = draftPath();
        base.delete();
        new File(base.getPath() + ".bak").delete();
        new File(base.getPath() + ".new").delete();
    }

    @Test public void defaultPartAndStopLeaveTheFinalDotForKeyboardPunctuation() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Send me the address, please.", 0));
        assertEquals("Send me the address, please", editor.text.toString().trim());
        voice.onDictationComplete(SESSION, 0, "Send me the address, please.", "");
        assertEquals("Send me the address, please", editor.text.toString().trim());
    }

    @Test public void questionMarkIsPresentBeforeTheNextUserEdit() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "What do you think?", 0));
        assertEquals("What do you think? ", editor.text.toString());
        editor.replaceSelection("Yes"); voice.onUserEdit();
        voice.onDictationComplete(SESSION, 0, "What do you think?", "");
        assertEquals("What do you think? Yes", editor.text.toString());
    }

    @Test public void cursorMoveNeverDropsTheSeparatorBeforeExistingText() throws Exception {
        FakeEditor editor = new FakeEditor("Existing paragraph 0000"); editor.cursor = 0;
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Second point, the venue is booked.", 0));
        assertTrue(editor.text.toString().contains("booked Existing paragraph"));
        editor.cursor = editor.text.length(); voice.onUserEdit();
        voice.onDictationComplete(SESSION, 0, "Second point, the venue is booked.", "");
        assertTrue(editor.text.toString().contains("booked Existing paragraph"));
    }

    @Test public void automaticCloseKeepsTheConfirmedFinalTailCopyable() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        voice.onInputViewFinished(true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Thanks a lot.", 0));
        voice.onDictationComplete(SESSION, 0, "Thanks a lot.", "");
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        assertTrue(clip().startsWith("Thanks a lot"));
        assertFalse(host.state.canCopy);
    }

    @Test public void copyOffersOnlyNewestRecordingAndDoesNotWalkBackThroughOlderOnes() throws Exception {
        FakeEditor editor = new FakeEditor(""); editor.accepts = false;
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First result.", 0));
        voice.onDictationComplete(SESSION, 0, "First result.", "");
        recording(host, editor, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Second result.", 0));
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 1, "Its second piece.", 4));
        voice.onDictationComplete(SESSION + 1, 0, "Second result. Its second piece.", "");
        assertTrue(voice.copyDraft());
        assertEquals("Second result. Its second piece.", clip().trim());
        assertFalse(host.state.canCopy);
        assertFalse(voice.copyDraft());
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);
    }

    @Test public void ambiguousLegacyAggregateStaysSavedButIsNotOfferedAsLatest() throws Exception {
        File legacy = new File(context.getNoBackupFilesDir(), "pending-dictation");
        byte[] old = ("NOTUNE1\n77\n2\ninterrupted\n" + java.util.Base64.getEncoder()
                .encodeToString("First result.\nSecond result.".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(legacy.toPath(), old);
        try {
            FakeHost host = new FakeHost(new FakeEditor(""));
            RustInputMethodService voice = new RustInputMethodService(context, host);
            assertFalse(host.state.canCopy);
            assertFalse(voice.copyDraft());
            org.junit.Assert.assertArrayEquals(old, Files.readAllBytes(legacy.toPath()));
        } finally { legacy.delete(); }
    }

    @Test public void confirmedPieceRetiresOlderCopyBeforeTerminalCallback() throws Exception {
        FakeEditor first = new FakeEditor(""); first.accepts = false;
        FakeHost host = new FakeHost(first); host.ready = true;
        RustInputMethodService voice = recording(host, first, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Older result.", 0));
        voice.onDictationComplete(SESSION, 0, "Older result.", "");
        FakeEditor next = new FakeEditor("");
        host.switchTo(next, new EditorRecord("test.app", 8, TEXT, 0, false));
        voice.onEditorStarted(false);
        recording(host, next, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Newest result", 0));
        assertFalse(draftPath().exists());
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);
    }

    @Test public void acknowledgedLateWordsSurviveRestartBeforeCompletion() throws Exception {
        FakeEditor first = new FakeEditor("");
        FakeHost host = new FakeHost(first);
        host.ready = true;
        RustInputMethodService voice = recording(host, first, SESSION);
        host.switchTo(new FakeEditor(""), new EditorRecord("other.app", 9, TEXT, 0, false));
        voice.onEditorStarted(false);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Words after leaving.", 0));
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        RustInputMethodService restored = new RustInputMethodService(context, reopened);
        assertTrue(reopened.state.canCopy);
        assertTrue(restored.copyDraft());
        assertEquals("Words after leaving.", clip());
    }

    @Test public void refusedCommitSurvivesRestartBeforeCompletion() throws Exception {
        FakeEditor first = new FakeEditor("");
        first.accepts = false;
        FakeHost host = new FakeHost(first);
        host.ready = true;
        RustInputMethodService voice = recording(host, first, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Refused words.", 0));
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        RustInputMethodService restored = new RustInputMethodService(context, reopened);
        assertTrue(reopened.state.canCopy);
        assertTrue(restored.copyDraft());
        assertEquals("Refused words.", clip().trim());
        assertFalse(draftPath().exists());
    }

    @Test public void swipeBeforeFirstVoicePieceUsesNewCursorAndKeepsFinalMark() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor);
        RustInputMethodService voice = recording(host, editor, SESSION);

        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));
        assertEquals("", editor.text.toString());
        assertEquals("World.", savedDraft().text);

        editor.replaceSelection("hello");
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("hello world ", editor.text.toString());
        assertEquals(1, editor.commits);

        voice.onDictationComplete(SESSION, 0, "World.", "");
        assertEquals("hello world. ", editor.text.toString());
        assertEquals(2, editor.commits);
        assertFalse(draftPath().exists());
    }

    @Test public void movedCursorKeepsTextOnBothSides() throws Exception {
        FakeEditor editor = new FakeEditor("alpha beta");
        editor.cursor = 6;
        FakeHost host = new FakeHost(editor);
        RustInputMethodService voice = recording(host, editor, SESSION);

        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));
        editor.replaceSelection("hello ");
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("alpha hello world beta", editor.text.toString());
        assertEquals(1, editor.commits);
    }

    @Test public void anotherFieldStopsDeliveryAndOffersTheWordsThere() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor);
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));

        FakeEditor other = new FakeEditor("");
        host.switchTo(other, new EditorRecord("other.app", 9, TEXT, 0, false));
        host.ready = true;
        voice.onEditorStarted(false);
        voice.resumePendingDelivery();
        assertEquals("", editor.text.toString());
        assertEquals("", other.text.toString());
        assertEquals(0, editor.commits + other.commits);
        voice.onDictationComplete(SESSION, 0, "World.", "");
        assertTrue(host.state.canCopy);
        assertEquals("World.", savedDraft().text);
    }

    @Test public void closePolicyKeepsListeningOnlyInTheSameField() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        voice.onInputViewFinished(false);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));
        assertEquals(RustInputMethodService.Phase.RECORDING, host.state.phase);
        assertTrue(editor.text.toString().toLowerCase().contains("buy milk"));

        FakeEditor other = new FakeEditor("");
        host.switchTo(other, new EditorRecord("other.app", 9, TEXT, 0, false));
        voice.onEditorStarted(false);
        assertEquals(RustInputMethodService.Phase.FINISHING, host.state.phase);
        assertEquals("", other.text.toString());
    }

    @Test public void closePolicyStopsWithoutDiscardingTheFinalPiece() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        voice.onInputViewFinished(true);
        assertEquals(RustInputMethodService.Phase.FINISHING, host.state.phase);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));
        voice.onDictationComplete(SESSION, 0, "Buy milk.", "");
        assertEquals("note: buy milk. ", editor.text.toString().toLowerCase());
    }

    @Test public void theSameDocumentComingBackReceivesTheWords() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        EditorRecord record = host.editor;

        voice.onInputViewFinished(true);           // Home: the keyboard view finishes
        host.editor = null;                    // then onFinishInput
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));
        voice.onDictationComplete(SESSION, 0, "Buy milk.", "");
        assertEquals("Note: ", editor.text.toString());

        host.editor = record;                  // the same field returns unchanged
        voice.onEditorStarted(false);
        assertEquals("Note: buy milk. ", editor.text.toString().replace("Buy", "buy"));
        assertTrue(host.state.canCopy); // Automatic close now keeps even confirmed text copyable.
        assertTrue(draftPath().exists());
    }

    @Test public void homeShowsTheLauncherAndTheSameDocumentStillReceivesTheWords() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        EditorRecord record = host.editor;
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));

        voice.onInputViewFinished(true);
        FakeEditor launcher = new FakeEditor("");
        // Observed after Home: the launcher starts input with type 0, options 0, id 0.
        host.switchTo(launcher, new EditorRecord("launcher", 0, InputType.TYPE_NULL, 0, false));
        voice.onEditorStarted(false);
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "And eggs.", 4f));
        voice.onDictationComplete(SESSION, 0, "Buy milk. And eggs.", "");
        assertEquals("", launcher.text.toString());

        host.switchTo(editor, record);          // the same note comes back unchanged
        voice.onEditorStarted(false);
        assertEquals("note: buy milk. and eggs. ", editor.text.toString().toLowerCase());
        assertTrue(host.state.canCopy); // Automatic close now keeps even confirmed text copyable.
    }

    @Test public void lockScreenReturnDeliversAlthoughTheFieldWasUnreadableWhenLeft() throws Exception {
        // Emulator order (lock and Home): at onFinishInputView the app's connection is already
        // inactive and reads return null; then onFinishInput clears the editor record.
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        EditorRecord record = host.editor;
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));

        editor.readable = false;
        voice.onInputViewFinished(true);
        host.connection = null;
        host.editor = null;
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "And eggs.", 4f));
        voice.onDictationComplete(SESSION, 0, "Buy milk. And eggs.", "");

        editor.readable = true;
        host.switchTo(editor, record);          // unlock: the same note, unchanged
        voice.onEditorStarted(false);
        assertEquals("note: buy milk. and eggs. ", editor.text.toString().toLowerCase());
        assertTrue(host.state.canCopy); // Automatic close now keeps even confirmed text copyable.

        // Opposite: the note changed while away, so the words go to Copy instead.
        FakeEditor other = new FakeEditor("Note: ");
        FakeHost host2 = new FakeHost(other);
        host2.ready = true;
        RustInputMethodService voice2 = recording(host2, other, SESSION + 1);
        EditorRecord record2 = host2.editor;
        assertTrue(voice2.onTranscriptPiece(SESSION + 1, 0, "Buy milk.", 0));
        other.readable = false;
        voice2.onInputViewFinished(true);
        host2.connection = null;
        host2.editor = null;
        assertTrue(voice2.onTranscriptPiece(SESSION + 1, 1, "And eggs.", 4f));
        voice2.onDictationComplete(SESSION + 1, 0, "Buy milk. And eggs.", "");
        other.readable = true;
        other.text.append("typed elsewhere");
        other.cursor = other.text.length();
        host2.switchTo(other, record2);
        voice2.onEditorStarted(false);
        assertFalse(other.text.toString().toLowerCase().contains("eggs"));
        assertTrue(host2.state.canCopy);
    }

    @Test public void wordsAfterTheFieldIsLostKeepTheirFullStopInCopy() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));
        voice.onInputViewFinished(true);
        host.switchTo(new FakeEditor(""), new EditorRecord("other.app", 9, TEXT, 0, false));
        voice.onEditorStarted(false);
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "And eggs.", 4f));
        voice.onDictationComplete(SESSION, 0, "Buy milk. And eggs.", "");
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        // Opposite (seen on the emulator before the fix): "buy milkAnd eggs."
        assertEquals("buy milk. and eggs.", clip().trim().toLowerCase());
        voice.resumePendingDelivery();
        assertFalse(draftPath().exists());
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);
    }

    @Test public void anotherDocumentWithTheSameWidgetIdDoesNotReceiveTheWords() throws Exception {
        FakeEditor editor = new FakeEditor("Note: ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        EditorRecord record = host.editor;

        voice.onInputViewFinished(true);
        host.editor = null;
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Buy milk.", 0));
        voice.onDictationComplete(SESSION, 0, "Buy milk.", "");

        FakeEditor otherDocument = new FakeEditor("Note: ");
        otherDocument.text.append("\nother file");
        host.switchTo(otherDocument, record);  // same package, id and type
        voice.onEditorStarted(false);
        assertEquals("Note: \nother file", otherDocument.text.toString());
        assertEquals(0, otherDocument.commits);
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        assertEquals("Buy milk.", clip());
    }

    @Test public void rotationKeepsTheBindingEvenWhenTheFieldCannotBeRead() throws Exception {
        FakeEditor editor = new FakeEditor("");
        editor.readable = false;
        FakeHost host = new FakeHost(editor, new EditorRecord("com.termux", 5, InputType.TYPE_NULL,
                0, false));
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First sentence.", 0));
        voice.onEditorStarted(true);
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "Second sentence.", 4));
        assertEquals("First sentence. Second sentence. ", editor.text.toString());
        assertEquals(RustInputMethodService.Phase.RECORDING, host.state.phase);

        // Without rotation, an unreadable field that restarts is ambiguous: no insertion.
        voice.onEditorStarted(false);
        assertTrue(voice.onTranscriptPiece(SESSION, 2, "Third.", 4));
        assertEquals("First sentence. Second sentence. ", editor.text.toString());
    }

    @Test public void wordsDuringTheRotationWindowGoIntoTheRecreatedFieldOnce() throws Exception {
        // Emulator order (Markor, user_rotation 1 then 0): the activity is recreated, and a
        // commit sent between the two onStartInput calls was lost (read-back mismatch).
        FakeEditor editor = new FakeEditor("Start. ");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Hi Sam, I will be late.", 0));
        host.ready = false; // HeliBoard's rotation window is open
        voice.onEditorStarted(true);
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "We can meet at the gate.", 4f));
        FakeEditor recreated = new FakeEditor(editor.text.toString());
        host.switchTo(recreated, new EditorRecord("test.app", 7, TEXT, 0, false));
        voice.onEditorStarted(true);
        assertTrue(voice.onTranscriptPiece(SESSION, 2, "Thanks.", 4f));
        voice.onDictationComplete(SESSION, 0, "", "");
        assertEquals(0, recreated.commits);
        // The window ends.
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("Start. Hi Sam, I will be late. We can meet at the gate. Thanks. ",
                recreated.text.toString());
        assertEquals(1, editor.commits);
        assertFalse(host.state.canCopy);
        assertEquals(RustInputMethodService.Phase.IDLE, host.state.phase);
    }

    @Test public void terminalCallbackWaitsForTwoRawPiecesThenFinishesOnce() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor);
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));
        assertTrue(voice.onTranscriptPiece(SESSION, 1, "Again.", 1));
        assertEquals("World. Again.", savedDraft().text);

        voice.onDictationComplete(SESSION, 0, "World. Again.", "");
        assertEquals(RustInputMethodService.Phase.FINISHING, host.state.phase);
        assertEquals("", editor.text.toString());

        editor.replaceSelection("hello");
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("hello world again. ", editor.text.toString());
        assertEquals(RustInputMethodService.Phase.IDLE, host.state.phase);
        assertFalse(host.state.canCopy);
    }

    @Test public void copyDoesNotDependOnOptionalHistorySettings() throws Exception {
        for (boolean history : new boolean[] {false, true}) {
            clearRecovery();
            FakeEditor editor = new FakeEditor("");
            editor.reflects = false;           // accepts the commit, reads back unchanged
            FakeHost host = new FakeHost(editor);
            host.ready = true;
            host.history = history;
            RustInputMethodService voice = recording(host, editor, SESSION);
            set(voice, "maySaveCurrentSession", host.maySaveDictation(host.editor));
            assertTrue(voice.onTranscriptPiece(SESSION, 0, "Unconfirmed words.", 0));
            voice.onDictationComplete(SESSION, 0, "Unconfirmed words.", "");
            assertTrue("history " + history, host.state.canCopy);
            assertEquals(history ? 1 : 0, host.saved.size());
            assertTrue(voice.copyDraft());
            assertEquals("Unconfirmed words. ", clip());
            assertFalse(host.state.canCopy);
        }
    }

    @Test public void unconfirmedCopyEndsAtTheFirstEditButUndeliveredCopyStays() throws Exception {
        FakeEditor editor = new FakeEditor("");
        editor.reflects = false;
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Unconfirmed words.", 0));
        voice.onDictationComplete(SESSION, 0, "Unconfirmed words.", "");
        assertTrue(host.state.canCopy);
        editor.replaceSelection("x");
        voice.onUserEdit();
        voice.resumePendingDelivery();         // the key's finger-up follows the edit
        assertFalse(host.state.canCopy);

        // Opposite: words the editor refused were never delivered, so an edit keeps them.
        FakeEditor refusing = new FakeEditor("");
        refusing.accepts = false;
        host.switchTo(refusing, new EditorRecord("chat.app", 5, TEXT, 0, false));
        voice.onEditorStarted(false);
        recording(host, refusing, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Refused words.", 0));
        voice.onDictationComplete(SESSION + 1, 0, "Refused words.", "");
        assertTrue(host.state.canCopy);
        voice.onUserEdit();
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        assertEquals("Refused words.", clip().trim());
    }

    @Test public void unconfirmedCopyEndsWhenTheUserLeavesTheField() throws Exception {
        FakeEditor editor = new FakeEditor("");
        editor.reflects = false;
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Maybe there.", 0));
        voice.onDictationComplete(SESSION, 0, "Maybe there.", "");
        assertTrue(host.state.canCopy);
        host.switchTo(new FakeEditor(""), new EditorRecord("other.app", 9, TEXT, 0, false));
        voice.onEditorStarted(false);
        assertFalse(host.state.canCopy);
    }

    @Test @org.robolectric.annotation.Config(sdk = {26, 33})
    public void privateFieldInsertsWithoutADraftAndCopyStaysInThatField() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor, new EditorRecord("bank.app", 3, PASSWORD, 0, false));
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "sentinel seven", 0));
        assertEquals("sentinel seven", editor.text.toString());
        assertFalse(draftPath().exists());
        voice.onDictationComplete(SESSION, 0, "sentinel seven", "");
        assertFalse(host.state.canCopy);

        FakeEditor refusing = new FakeEditor("");
        refusing.accepts = false;
        host.switchTo(refusing, new EditorRecord("bank.app", 4, PASSWORD, 0, false));
        voice.onEditorStarted(false);
        RustInputMethodService second = recording(host, refusing, SESSION + 1, voice);
        assertTrue(second.onTranscriptPiece(SESSION + 1, 0, "sentinel eight", 0));
        second.onDictationComplete(SESSION + 1, 0, "sentinel eight", "");
        assertFalse(draftPath().exists());
        assertTrue(host.state.canCopy);        // explicit Copy in its own field
        assertTrue(second.copyDraft());
        assertEquals("sentinel eight", clip().trim());
        assertTrue(clipIsSensitive());
        assertFalse(host.state.canCopy);
        RustInputMethodService third = recording(host, refusing, SESSION + 2, second);
        assertTrue(third.onTranscriptPiece(SESSION + 2, 0, "sentinel nine", 0));
        third.onDictationComplete(SESSION + 2, 0, "sentinel nine", "");
        assertTrue(host.state.canCopy);
        host.switchTo(new FakeEditor(""), new EditorRecord("chat.app", 1, TEXT, 0, false));
        third.onEditorStarted(false);
        assertFalse(host.state.canCopy);       // never exposed in another field
    }

    @Test public void confirmedNewDictationRetiresOlderCopyAcrossRestart() throws Exception {
        FakeEditor refusing = new FakeEditor("");
        refusing.accepts = false;
        FakeHost host = new FakeHost(refusing);
        host.ready = true;
        RustInputMethodService voice = recording(host, refusing, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First result.", 0));
        voice.onDictationComplete(SESSION, 0, "First result.", "");
        assertTrue(host.state.canCopy);

        FakeEditor good = new FakeEditor("");
        host.switchTo(good, new EditorRecord("chat.app", 1, TEXT, 0, false));
        voice.onEditorStarted(false);
        recording(host, good, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Second result.", 0));
        voice.onDictationComplete(SESSION + 1, 0, "Second result.", "");
        assertEquals("Second result. ", good.text.toString());
        assertFalse(host.state.canCopy);
        assertFalse(voice.copyDraft());
        assertFalse(draftPath().exists());
        FakeHost reopened = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);
    }

    @Test public void numberFieldGetsDigitsOrNothingAndCopyKeepsTheWords() throws Exception {
        int decimal = InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor, new EditorRecord("shop.app", 2, decimal, 0, false));
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "12.", 0));
        voice.onDictationComplete(SESSION, 0, "12.", "");
        assertEquals("12", editor.text.toString());
        assertFalse(host.state.canCopy);

        FakeEditor holdsDecimal = new FakeEditor("3.5");
        host.switchTo(holdsDecimal, new EditorRecord("shop.app", 3, decimal, 0, false));
        voice.onEditorStarted(false);
        recording(host, holdsDecimal, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "2.5", 0));
        voice.onDictationComplete(SESSION + 1, 0, "2.5", "");
        assertEquals("3.5", holdsDecimal.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("2.5", clip());

        FakeEditor empty = new FakeEditor("");
        host.switchTo(empty, new EditorRecord("shop.app", 4, InputType.TYPE_CLASS_NUMBER, 0, false));
        voice.onEditorStarted(false);
        recording(host, empty, SESSION + 2, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 2, 0, "1/2", 0));
        voice.onDictationComplete(SESSION + 2, 0, "1/2", "");
        assertEquals("", empty.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("1/2", clip());
    }

    @Test public void silenceShowsNothingHeardBrieflyAndItIsNotAFailure() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor);
        host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        voice.onDictationComplete(SESSION, 0, "", "");
        assertTrue(host.state.notice);
        assertFalse(host.state.error);
        assertEquals(context.getString(helium314.keyboard.latin.R.string.voice_status_nothing_heard),
                host.state.message);
        assertEquals(3000L, host.delay);
        host.delayed.run();
        assertFalse(host.state.notice);

        // Opposite: a refused commit is an undelivered result, not "Nothing heard".
        FakeEditor refusing = new FakeEditor("");
        refusing.accepts = false;
        host.switchTo(refusing, new EditorRecord("chat.app", 1, TEXT, 0, false));
        voice.onEditorStarted(false);
        recording(host, refusing, SESSION + 1, voice);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Hello.", 0));
        voice.onDictationComplete(SESSION + 1, 0, "Hello.", "");
        assertFalse(host.state.notice);
        assertTrue(host.state.canCopy);
    }

    @Test public void copiedDraftDisappearsAcrossReopenAndNextDictation() throws Exception {
        seedRecovery("first dictation");
        FakeHost host = new FakeHost(new FakeEditor(""));
        RustInputMethodService voice = new RustInputMethodService(context, host);
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        assertEquals("first dictation", clip());
        assertFalse(host.state.canCopy);
        assertFalse(draftPath().exists());

        FakeHost reopened = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);

        seedRecovery("second dictation");
        RustInputMethodService next = new RustInputMethodService(context, reopened);
        assertTrue(next.copyDraft());
        assertEquals("second dictation", clip());
    }

    @Test public void punctuationOnlyDraftIsNotOffered() throws Exception {
        seedRecovery(". ");
        FakeHost host = new FakeHost(new FakeEditor(""));
        new RustInputMethodService(context, host);
        assertFalse(host.state.canCopy);
        assertFalse(draftPath().exists());
    }

    @Test public void failedClipboardWriteAndStaleCopyKeepRecovery() throws Exception {
        seedRecovery("uncopied text");
        ClipboardManager failingClipboard = mock(ClipboardManager.class);
        doThrow(new SecurityException("test failure")).when(failingClipboard)
                .setPrimaryClip(any(ClipData.class));
        Context failingContext = new ContextWrapper(context) {
            @Override public Context getApplicationContext() { return this; }
            @Override public Object getSystemService(String name) {
                return Context.CLIPBOARD_SERVICE.equals(name) ? failingClipboard : super.getSystemService(name);
            }
        };
        FakeHost host = new FakeHost(new FakeEditor(""));
        RustInputMethodService voice = new RustInputMethodService(failingContext, host);
        assertFalse(voice.copyDraft());
        assertTrue(host.state.canCopy);
        assertEquals("uncopied text", savedDraft().text);

        set(voice, "terminal", false);
        set(voice, "phase", RustInputMethodService.Phase.RECORDING);
        assertFalse(voice.copyDraft());
        assertEquals("uncopied text", savedDraft().text);
    }

    @Test public void copyCleanupFailureDoesNotClaimRetirement() throws Exception {
        seedRecovery("keep for recovery");
        FakeHost host = new FakeHost(new FakeEditor(""));
        RustInputMethodService voice = new RustInputMethodService(context, host);
        File backup = new File(draftPath().getPath() + ".bak");
        assertTrue(backup.mkdir());
        File block = new File(backup, "block");
        Files.write(block.toPath(), new byte[] {1});
        try {
            assertFalse(voice.copyDraft());
            assertTrue(host.state.canCopy);
            assertEquals(context.getString(helium314.keyboard.latin.R.string.voice_status_copy_retire_failed),
                    host.state.message);
        } finally {
            block.delete();
            backup.delete();
        }
    }

    @Test public void earlierCompletionDoesNotStopNewCaptureAndCopyContainsOnlyTheNewRecording() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        Object a = pending(voice, host, SESSION, 1000, false);
        set(voice, "delivering", a);
        set(a, "stoppedAt", 2000L);
        Object b = pending(voice, host, SESSION + 1, 2100, true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Send me the address, please.", 0));
        voice.onDictationComplete(SESSION, 0, "Send me the address, please.", "");
        assertEquals(RustInputMethodService.Phase.RECORDING, host.state.phase);
        assertEquals(SESSION + 1, get(voice, "captureId"));
        voice.onAutoStop(SESSION);
        assertEquals(SESSION + 1, get(voice, "captureId"));
        voice.onTranscriptionBusy(SESSION + 1, true);
        assertEquals(-1, voice.transcribeNow());
        voice.onInputViewFinished(true); // Stop must still be possible while inference is busy.
        assertEquals(0, get(voice, "captureId"));
        voice.onTranscriptionBusy(SESSION + 1, false);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Thanks a lot!", 0));
        voice.onDictationComplete(SESSION + 1, 0, "Thanks a lot!", "");
        assertEquals("Send me the address, please thanks a lot! ", editor.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("thanks a lot!", clip().trim());
    }

    @Test public void nextSentenceReplacesOnlyTheOwnedSpaceAndDoesNotCopyThePreviousDot() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        Object a = pending(voice, host, SESSION, 1000, false);
        set(voice, "delivering", a); set(a, "stoppedAt", 2000L);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First point.", 0));
        voice.onDictationComplete(SESSION, 0, "First point.", "");
        Object b = pending(voice, host, SESSION + 1, 7000, false);
        activate(voice, b);
        voice.onInputViewFinished(true);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Second point.", 0));
        voice.onDictationComplete(SESSION + 1, 0, "Second point.", "");
        assertEquals("First point. Second point ", editor.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("Second point", clip().trim());
    }

    @Test public void queuedAcknowledgedWordsSurviveRestartAndReplayAfterTheFingerLifts() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = false;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "alwaysFullStop", false);
        Object a = pending(voice, host, SESSION, 1000, false);
        set(voice, "delivering", a); set(a, "stoppedAt", 2000L);
        Object b = pending(voice, host, SESSION + 1, 2200, true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First point.", 0));
        voice.onDictationComplete(SESSION, 0, "First point.", "");
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Second point.", 0));
        assertEquals("Second point.", savedDraft().text);
        FakeHost restoredHost = new FakeHost(new FakeEditor(""));
        RustInputMethodService restored = new RustInputMethodService(context, restoredHost);
        assertTrue(restored.copyDraft());
        assertEquals("Second point.", clip());
        voice.onInputViewFinished(true);
        voice.onDictationComplete(SESSION + 1, 0, "Second point.", "");
        assertEquals("", editor.text.toString());
        host.ready = true; voice.resumePendingDelivery();
        assertEquals("First point second point ", editor.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("second point", clip().trim());
    }

    @Test public void queuedNewFieldKeepsItsBindingAcrossCloseAndReopen() throws Exception {
        FakeEditor first = new FakeEditor("");
        FakeHost host = new FakeHost(first); host.ready = true;
        RustInputMethodService voice = recording(host, first, SESSION);
        set(voice, "alwaysFullStop", false);
        Object a = pending(voice, host, SESSION, 1000, false);
        set(voice, "delivering", a); set(a, "stoppedAt", 2000L);
        set(voice, "captureId", 0L); set(voice, "recording", false);
        FakeEditor second = new FakeEditor("New note: ");
        host.switchTo(second, new EditorRecord("second.app", 9, TEXT, 0, false));
        voice.onEditorStarted(false);
        long binding = get(voice, "bindingGeneration");
        pending(voice, host, SESSION + 1, 2200, true);
        voice.onInputViewFinished(true);
        voice.onEditorStarted(true);
        voice.onEditorStarted(false);
        assertEquals(binding, get(voice, "bindingGeneration"));
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Old field words.", 0));
        voice.onDictationComplete(SESSION, 0, "Old field words.", "");
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "New field words.", 0));
        voice.onDictationComplete(SESSION + 1, 0, "New field words.", "");
        assertEquals("", first.text.toString());
        assertEquals("New note: New field words ", second.text.toString());
        assertTrue(voice.copyDraft());
        assertEquals("New field words", clip().trim());
    }


    @Test public void privacyChangeDuringRecordingPreventsDraftHistoryAndPublicCopy() throws Exception {
        FakeEditor editor = new FakeEditor(""); editor.accepts = false;
        FakeHost host = new FakeHost(editor); host.ready = true; host.history = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "maySaveCurrentSession", true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Private later.", 0));
        assertTrue(draftPath().exists());
        host.editor = new EditorRecord("test.app", 7, TEXT, 0, true);
        voice.onEditorStarted(true);
        assertFalse(draftPath().exists());
        host.editor = new EditorRecord("test.app", 7, TEXT, 0, false);
        voice.onEditorStarted(true);
        voice.onInputViewFinished(true);
        voice.onDictationComplete(SESSION, 0, "Private later.", "");
        assertTrue(host.saved.isEmpty());
        assertTrue(voice.copyDraft());
        assertTrue(clipIsSensitive());
        assertFalse(draftPath().exists());
    }

    @Test public void privacyChangeCoversQueuedRecordingAfterIncognitoTurnsOff() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor); host.ready = true; host.history = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        Object a = pending(voice, host, SESSION, 1000, false);
        set(voice, "delivering", a); set(a, "stoppedAt", 2000L);
        set(voice, "maySaveCurrentSession", true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "First point.", 0));
        Object b = pending(voice, host, SESSION + 1, 2100, true);
        set(b, "saveHistory", true);
        assertTrue(voice.onTranscriptPiece(SESSION + 1, 0, "Private second point.", 0));
        assertTrue(draftPath().exists());
        host.editor = new EditorRecord("test.app", 7, TEXT, 0, true);
        voice.onEditorStarted(true);
        assertFalse(draftPath().exists());
        host.editor = new EditorRecord("test.app", 7, TEXT, 0, false);
        voice.onEditorStarted(true);
        voice.onInputViewFinished(true);
        voice.onDictationComplete(SESSION, 0, "First point.", "");
        voice.onDictationComplete(SESSION + 1, 0, "Private second point.", "");
        assertTrue(host.saved.isEmpty());
        assertTrue(voice.copyDraft());
        assertTrue(clip().contains("second point"));
        assertTrue(clipIsSensitive());
        assertFalse(draftPath().exists());
    }


    @Test public void privacyToggleProtectsUnreadableFieldWithUnknownId() throws Exception {
        FakeEditor editor = new FakeEditor(""); editor.accepts = false;
        FakeHost host = new FakeHost(editor, new EditorRecord("com.termux", 0, 0, 0, false));
        host.ready = true; host.history = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        set(voice, "maySaveCurrentSession", true);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Private terminal words.", 0));
        assertTrue(draftPath().exists());
        host.editor = new EditorRecord("com.termux", 0, 0, 0, true);
        voice.onEditorPrivacyChanged();
        assertFalse(draftPath().exists());
        voice.onInputViewFinished(true);
        voice.onDictationComplete(SESSION, 0, "Private terminal words.", "");
        assertTrue(host.saved.isEmpty());
        assertTrue(voice.copyDraft());
        assertTrue(clipIsSensitive());
    }

    @Test public void enteringAnotherPrivateFieldDoesNotReclassifyOldPublicRecovery() throws Exception {
        FakeEditor editor = new FakeEditor(""); editor.accepts = false;
        FakeHost host = new FakeHost(editor); host.ready = true;
        RustInputMethodService voice = recording(host, editor, SESSION);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "Public pending words.", 0));
        host.switchTo(new FakeEditor(""), new EditorRecord("other.app", 9, PASSWORD, 0, false));
        voice.onEditorStarted(false);
        voice.onDictationComplete(SESSION, 0, "Public pending words.", "");
        assertTrue(draftPath().exists());
    }

    private Object pending(RustInputMethodService voice, FakeHost host, long id, long started,
                           boolean enqueue) throws Exception {
        Class<?> type = Class.forName(RustInputMethodService.class.getName() + "$Recording");
        java.lang.reflect.Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Object item = ctor.newInstance(new VoiceInterval(id, host.editor,
                get(voice, "bindingGeneration")), false);
        set(item, "startedAt", started);
        set(voice, "captureId", id); set(voice, "recording", true);
        if (enqueue) {
            Field field = voice.getClass().getDeclaredField("waiting"); field.setAccessible(true);
            ((ArrayList<Object>) field.get(voice)).add(item);
        }
        return item;
    }

    private void activate(RustInputMethodService voice, Object item) throws Exception {
        java.lang.reflect.Method method = voice.getClass().getDeclaredMethod("activate", item.getClass());
        method.setAccessible(true); method.invoke(voice, item);
    }

    private String clip() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
    }

    private boolean clipIsSensitive() {
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        android.os.PersistableBundle extras = clipboard.getPrimaryClip().getDescription().getExtras();
        return extras != null && extras.getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE);
    }

    private File draftPath() { return new File(context.getNoBackupFilesDir(), "pending-dictation-latest"); }

    private void seedRecovery(String text) throws Exception {
        Files.write(draftPath().toPath(),
                new PendingDictationDraft(SESSION, 1, PendingDictationDraft.UNCERTAIN, text).encode());
    }

    private PendingDictationDraft savedDraft() throws Exception {
        return PendingDictationDraft.decode(Files.readAllBytes(draftPath().toPath()));
    }

    private RustInputMethodService recording(FakeHost host, FakeEditor editor, long id)
            throws Exception {
        return recording(host, editor, id, new RustInputMethodService(context, host));
    }

    /** What start() sets once native recording has started; native is absent in tests. */
    private RustInputMethodService recording(FakeHost host, FakeEditor editor, long id,
                                             RustInputMethodService voice) throws Exception {
        set(voice, "activeSessionId", id);
        set(voice, "captureId", id);
        // Existing scenarios retain the selectable old mode. New policy cases override it.
        set(voice, "alwaysFullStop", true);
        set(voice, "nextPieceSequence", 0L);
        set(voice, "terminal", false);
        set(voice, "recording", true);
        set(voice, "phase", RustInputMethodService.Phase.RECORDING);
        set(voice, "session", new VoiceInterval(id, host.editor, get(voice, "bindingGeneration")));
        set(voice, "targetLost", false);
        set(voice, "autoDeliveryOpen", true);
        set(voice, "numberRefused", false);
        set(voice, "sessionRawText", "");
        set(voice, "undeliveredText", "");
        set(voice, "hasAcceptedPiece", false);
        set(voice, "sentencePauseSeconds", 3f);
        set(voice, "expectedBefore", editor.readable ? editor.before() : null);
        set(voice, "expectedAfter", editor.readable ? editor.after() : null);
        set(voice, "expectedSelectionStart", editor.readable ? editor.cursor : -1);
        set(voice, "expectedSelectionEnd", editor.readable ? editor.cursor : -1);
        return voice;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static long get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.getLong(target);
    }

    private static final class FakeEditor {
        final StringBuilder text;
        final InputConnection connection = mock(InputConnection.class);
        int cursor;
        int commits;
        boolean accepts = true;
        boolean reflects = true;
        boolean readable = true;

        FakeEditor(String initial) {
            text = new StringBuilder(initial);
            cursor = text.length();
            when(connection.getTextBeforeCursor(anyInt(), anyInt()))
                    .thenAnswer(call -> readable ? before().substring(Math.max(0, cursor - (int) call.getArgument(0))) : null);
            when(connection.getTextAfterCursor(anyInt(), anyInt()))
                    .thenAnswer(call -> readable ? after() : null);
            when(connection.getExtractedText(any(ExtractedTextRequest.class), anyInt()))
                    .thenAnswer(call -> {
                        if (!readable) return null;
                        ExtractedText result = new ExtractedText();
                        result.text = text.toString();
                        result.startOffset = 0;
                        result.selectionStart = cursor;
                        result.selectionEnd = cursor;
                        return result;
                    });
            when(connection.deleteSurroundingText(anyInt(), anyInt())).thenAnswer(call -> {
                int count = call.getArgument(0);
                if (!accepts || count > cursor) return false;
                if (reflects) { text.delete(cursor - count, cursor); cursor -= count; }
                return true;
            });
            when(connection.commitText(any(CharSequence.class), anyInt()))
                    .thenAnswer(call -> {
                        if (!accepts) return false;
                        if (reflects) replaceSelection(call.getArgument(0).toString());
                        commits++;
                        return true;
                    });
        }

        String before() { return text.substring(0, cursor); }
        String after() { return text.substring(cursor); }
        void replaceSelection(String inserted) {
            text.insert(cursor, inserted);
            cursor += inserted.length();
        }
    }

    private static final class FakeHost implements RustInputMethodService.Host {
        InputConnection connection;
        final EditorInfo info = new EditorInfo();
        EditorRecord editor;
        boolean ready;
        boolean history;
        final ArrayList<String> saved = new ArrayList<>();
        Runnable delayed;
        long delay;
        RustInputMethodService.VoiceState state;

        FakeHost(FakeEditor field) {
            this(field, new EditorRecord("test.app", 7, TEXT, 0, false));
        }

        FakeHost(FakeEditor field, EditorRecord record) {
            switchTo(field, record);
        }

        void switchTo(FakeEditor field, EditorRecord record) {
            connection = field.connection;
            editor = record;
            info.inputType = record.inputType;
        }

        @Override public InputConnection currentInputConnection() { return connection; }
        @Override public EditorInfo currentEditorInfo() { return info; }
        @Override public EditorRecord currentEditor() { return editor; }
        @Override public boolean isMainThread() { return true; }
        @Override public void postToMain(Runnable action) { action.run(); }
        @Override public void postToMainDelayed(Runnable action, long delayMillis) {
            delayed = action;
            delay = delayMillis;
        }
        @Override public boolean prepareForVoiceCommit() { return ready; }
        @Override public boolean voiceCommitReady() { return ready; }
        @Override public boolean spacePendingBeforeVoice() { return false; }
        @Override public void finishVoiceCommit() { }
        @Override public void postVoiceState(RustInputMethodService.VoiceState state) {
            this.state = state;
        }
        @Override public boolean maySaveDictation(EditorRecord record) {
            return history && record != null && !record.privateField;
        }
        @Override public boolean saveDictation(long sessionId, String text) {
            saved.add(text);
            return true;
        }
    }
}
