package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
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

    private PendingDictationDraft savedDraft() throws Exception {
        File base = new File(context.getNoBackupFilesDir(), "pending-dictation");
        return PendingDictationDraft.decode(Files.readAllBytes(base.toPath()));
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
