package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
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
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
public class DeferredVoiceDeliveryTest {
    private static final long SESSION = 77;
    private Context context;

    @Before public void clearRecovery() {
        context = RuntimeEnvironment.getApplication();
        File base = new File(context.getNoBackupFilesDir(), "pending-dictation");
        base.delete();
        new File(base.getPath() + ".bak").delete();
        new File(base.getPath() + ".new").delete();
    }

    @Test public void swipeBeforeFirstVoicePieceUsesNewCursorAndKeepsFinalMark() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor.connection);
        RustInputMethodService voice = recording(host, editor);

        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));
        assertEquals("", editor.text.toString());
        assertEquals("World.", savedDraft().text);

        editor.replaceSelection("hello");
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("hello world", editor.text.toString());
        assertEquals(1, editor.commits);

        voice.onDictationComplete(SESSION, 0, "World.", "");
        assertEquals("hello world. ", editor.text.toString());
        assertEquals(2, editor.commits);
    }

    @Test public void movedCursorKeepsTextOnBothSides() throws Exception {
        FakeEditor editor = new FakeEditor("alpha beta");
        editor.cursor = 6;
        FakeHost host = new FakeHost(editor.connection);
        RustInputMethodService voice = recording(host, editor);

        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));
        editor.replaceSelection("hello ");
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("alpha hello world beta", editor.text.toString());
        assertEquals(1, editor.commits);
    }

    @Test public void changedTargetKeepsRawCopyWithoutWritingToOtherField() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor.connection);
        RustInputMethodService voice = recording(host, editor);
        assertTrue(voice.onTranscriptPiece(SESSION, 0, "World.", 0));

        host.identity = new Object();
        host.ready = true;
        voice.resumePendingDelivery();
        assertEquals("", editor.text.toString());
        assertEquals(0, editor.commits);
        assertFalse(host.state.canCopy);
        voice.onDictationComplete(SESSION, 0, "World.", "");
        assertTrue(host.state.canCopy);
        assertEquals("World.", savedDraft().text);
    }

    @Test public void terminalCallbackWaitsForTwoRawPiecesThenFinishesOnce() throws Exception {
        FakeEditor editor = new FakeEditor("");
        FakeHost host = new FakeHost(editor.connection);
        RustInputMethodService voice = recording(host, editor);
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

    @Test public void copiedDraftDisappearsAcrossReopenAndNextDictation() throws Exception {
        seedRecovery("first dictation");
        FakeHost host = new FakeHost(new FakeEditor("").connection);
        RustInputMethodService voice = new RustInputMethodService(context, host);
        assertTrue(host.state.canCopy);
        assertTrue(voice.copyDraft());
        ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals("first dictation", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertFalse(host.state.canCopy);
        assertFalse(draftPath().exists());

        FakeHost reopened = new FakeHost(new FakeEditor("").connection);
        new RustInputMethodService(context, reopened);
        assertFalse(reopened.state.canCopy);

        seedRecovery("second dictation");
        RustInputMethodService next = new RustInputMethodService(context, reopened);
        assertTrue(next.copyDraft());
        assertEquals("second dictation", clipboard.getPrimaryClip().getItemAt(0).getText().toString());
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
        FakeHost host = new FakeHost(new FakeEditor("").connection);
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
        FakeHost host = new FakeHost(new FakeEditor("").connection);
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

    private File draftPath() { return new File(context.getNoBackupFilesDir(), "pending-dictation"); }

    private void seedRecovery(String text) throws Exception {
        Files.write(draftPath().toPath(),
                new PendingDictationDraft(SESSION, 1, PendingDictationDraft.UNCERTAIN, text).encode());
    }

    private PendingDictationDraft savedDraft() throws Exception {
        return PendingDictationDraft.decode(Files.readAllBytes(draftPath().toPath()));
    }

    private RustInputMethodService recording(FakeHost host, FakeEditor editor) throws Exception {
        RustInputMethodService voice = new RustInputMethodService(context, host);
        set(voice, "activeSessionId", SESSION);
        set(voice, "terminal", false);
        set(voice, "recording", true);
        set(voice, "phase", RustInputMethodService.Phase.RECORDING);
        set(voice, "targetEditor", host.identity);
        set(voice, "autoDeliveryOpen", true);
        set(voice, "targetFieldKind", TextFitter.FieldKind.PROSE);
        set(voice, "sentencePauseSeconds", 3f);
        set(voice, "expectedBefore", editor.before());
        set(voice, "expectedAfter", editor.after());
        set(voice, "expectedSelectionStart", editor.cursor);
        set(voice, "expectedSelectionEnd", editor.cursor);
        return voice;
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class FakeEditor {
        final StringBuilder text;
        final InputConnection connection = mock(InputConnection.class);
        int cursor;
        int commits;

        FakeEditor(String initial) {
            text = new StringBuilder(initial);
            cursor = text.length();
            when(connection.getTextBeforeCursor(anyInt(), anyInt()))
                    .thenAnswer(call -> before());
            when(connection.getTextAfterCursor(anyInt(), anyInt()))
                    .thenAnswer(call -> after());
            when(connection.getExtractedText(any(ExtractedTextRequest.class), anyInt()))
                    .thenAnswer(call -> {
                        ExtractedText result = new ExtractedText();
                        result.text = text.toString();
                        result.startOffset = 0;
                        result.selectionStart = cursor;
                        result.selectionEnd = cursor;
                        return result;
                    });
            when(connection.commitText(any(CharSequence.class), anyInt()))
                    .thenAnswer(call -> {
                        replaceSelection(call.getArgument(0).toString());
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
        final InputConnection connection;
        final EditorInfo info = new EditorInfo();
        Object identity = new Object();
        boolean ready;
        RustInputMethodService.VoiceState state;

        FakeHost(InputConnection connection) { this.connection = connection; }
        @Override public InputConnection currentInputConnection() { return connection; }
        @Override public EditorInfo currentEditorInfo() { return info; }
        @Override public Object currentEditorIdentity() { return identity; }
        @Override public boolean inputActive() { return true; }
        @Override public boolean isMainThread() { return true; }
        @Override public void postToMain(Runnable action) { action.run(); }
        @Override public boolean prepareForVoiceCommit() { return ready; }
        @Override public boolean voiceCommitReady() { return ready; }
        @Override public void finishVoiceCommit() { }
        @Override public void postVoiceState(RustInputMethodService.VoiceState state) {
            this.state = state;
        }
        @Override public boolean maySaveDictation(EditorInfo info) { return false; }
        @Override public boolean saveDictation(long sessionId, String text) { return true; }
    }
}
