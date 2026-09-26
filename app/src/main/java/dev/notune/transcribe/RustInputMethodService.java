package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.util.AtomicFile;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import helium314.keyboard.latin.R;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Reusable NoTune dictation controller. HeliBoard remains the only
 * {@code InputMethodService} and the only owner of editor and keyboard UI state.
 *
 * <p>The class name is intentionally unchanged because the released native library
 * exports JNI symbols for {@code dev.notune.transcribe.RustInputMethodService}.
 * Extending {@link ContextWrapper} also gives native model loading the same
 * {@code getFilesDir()} and {@code getAssets()} contract as the original service.
 */
public final class RustInputMethodService extends ContextWrapper implements AutoCloseable {
    private static final String TAG = "NoTuneVoice";
    private static final String DRAFT_FILE = "pending-dictation";
    private static final int CONTEXT_BEFORE_CHARS = 2048;
    private static final int CONTEXT_AFTER_CHARS = 512;

    public static final String SETTING_SENTENCE_PAUSE_SECONDS = "pause_sentence_seconds";
    public static final String SETTING_SPLIT_SECONDS = "pause_split_seconds";
    public static final String SETTING_AUTO_STOP_SECONDS = "auto_stop_seconds";
    public static final String SETTING_SPEECH_SENSITIVITY = "speech_sensitivity";
    public static final String SETTING_ACTIVE_MODEL = "active_model";
    public static final String SETTING_MODEL_LANGUAGE = "model_language";
    public static final String SETTING_MODEL_TRANSLATE = "model_translate";
    public static final String SETTING_MODEL_THREADS = "model_threads";

    private static final int OUTCOME_SUCCESS = 0;
    private static final int OUTCOME_RETRYABLE = 1;
    private static final int OUTCOME_CANCELLED = 2;
    private static final int OUTCOME_INTERRUPTED = 3;
    private static final int OUTCOME_REVIEW = 5;

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError error) {
            Log.e(TAG, "Failed to load native libraries", error);
        }
    }

    /** HeliBoard supplies live editor state and a main-thread dispatcher. */
    public interface Host {
        InputConnection currentInputConnection();
        EditorInfo currentEditorInfo();

        /** Stable by equality within one field; changes before another field can receive text. */
        Object currentEditorIdentity();

        boolean inputActive();
        boolean isMainThread();
        void postToMain(Runnable action);

        /** Commits HeliBoard's active composing word before voice writes to the editor. */
        boolean prepareForVoiceCommit();

        /** Voice delivery must wait until a swipe result has reached the editor. */
        boolean voiceCommitReady();

        /** Reloads HeliBoard's editor state after the controller has attempted a write. */
        void finishVoiceCommit();

        /** Must enqueue or render state without opening a modal surface. */
        void postVoiceState(VoiceState state);

        /** Called at recording start, while the original field is still known. */
        boolean maySaveDictation(EditorInfo info);

        /** Writes or updates one history item for this recording. */
        boolean saveDictation(long sessionId, String text);
    }

    public enum Phase { IDLE, RECORDING, FINISHING }

    /** Immutable state for HeliBoard's existing toolbar and nonblocking recovery controls. */
    public static final class VoiceState {
        public final Phase phase;
        public final String message;
        public final float level;
        public final boolean canInsert;
        public final boolean canCopy;
        public final boolean canDiscard;
        public final boolean canRetry;
        public final boolean error;

        VoiceState(Phase phase, String message, float level, boolean canInsert,
                   boolean canCopy, boolean canDiscard, boolean canRetry, boolean error) {
            this.phase = phase;
            this.message = message;
            this.level = level;
            this.canInsert = canInsert;
            this.canCopy = canCopy;
            this.canDiscard = canDiscard;
            this.canRetry = canRetry;
            this.error = error;
        }
    }

    private final Host host;
    private final AtomicFile draftFile;
    private final PieceJoiner joiner = new PieceJoiner();

    private boolean initialized;
    private boolean terminal = true;
    private boolean recording;
    private boolean retryAvailable;
    private boolean retryingSession;
    private boolean stateError;
    private boolean autoDeliveryOpen;
    private Phase phase = Phase.IDLE;
    private String message;
    private float level;

    private long activeSessionId;
    private long nextPieceSequence;
    private Object targetEditor;
    private String expectedBefore;
    private String expectedAfter;
    private int expectedSelectionStart = -1;
    private int expectedSelectionEnd = -1;
    private TextFitter.FieldKind targetFieldKind = TextFitter.FieldKind.PROSE;
    private int targetCapsMode;
    private boolean hasAcceptedPiece;
    private float sentencePauseSeconds = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;

    private String undeliveredText = "";
    private String sessionCopyText = "";
    private static final class DeferredPiece {
        final String text;
        final float pauseBeforeSeconds;

        DeferredPiece(String text, float pauseBeforeSeconds) {
            this.text = text;
            this.pauseBeforeSeconds = pauseBeforeSeconds;
        }
    }
    private final ArrayList<DeferredPiece> deferredPieces = new ArrayList<>();
    private int deferredCopyStart = -1;
    private boolean completionDeferred;
    private int deferredOutcome;
    private String deferredFinalText;
    private String deferredError;
    private String sessionRecoveryBase = "";
    private boolean currentSessionNeedsRecovery;
    private boolean maySaveCurrentSession;
    private String retainedRecoveryText = "";
    private PendingDictationDraft pendingDraft;
    private boolean draftReadError;

    public RustInputMethodService(Context context, Host host) {
        super(context.getApplicationContext());
        if (host == null) throw new IllegalArgumentException("host == null");
        this.host = host;
        draftFile = new AtomicFile(new File(getNoBackupFilesDir(), DRAFT_FILE));
        message = getString(R.string.voice_status_initializing);
        restoreDraft();
        publishState();
    }

    /** Loads the native engine once. Call from HeliBoard's main thread. */
    public boolean initialize() {
        if (initialized) return true;
        try {
            initNative(this);
            initialized = true;
            message = getString(R.string.voice_status_ready);
        } catch (Throwable error) {
            Log.e(TAG, "Native initialization failed", error);
            message = getString(R.string.voice_status_unavailable);
            stateError = true;
        }
        publishState();
        return initialized;
    }

    /** Starts one recording bound to the current editor identity. */
    public boolean start() {
        if (!initialized || !terminal || !host.isMainThread()) return false;
        InputConnection connection = host.currentInputConnection();
        EditorInfo info = host.currentEditorInfo();
        Object editor = host.currentEditorIdentity();
        if (!host.inputActive() || connection == null || info == null || editor == null) {
            message = getString(R.string.voice_status_no_field);
            stateError = true;
            publishState();
            return false;
        }
        if (!undeliveredText.isEmpty()) {
            deliverStagedText();
            if (!undeliveredText.isEmpty()) {
                message = getString(R.string.voice_status_pending_first);
                stateError = true;
                publishState();
                return false;
            }
        }
        // Some editors reject HeliBoard composition but still accept a direct commit.
        if (!prepareHostForVoiceCommit()) {
            message = getString(R.string.voice_status_finish_gesture);
            stateError = true;
            publishState();
            return false;
        }

        final long candidateSessionId = Math.max(
                activeSessionId + 1, Math.max(1, System.nanoTime()));
        final TextFitter.FieldKind candidateFieldKind = FieldKinds.of(info);
        int candidateCapsMode;
        try {
            candidateCapsMode = connection.getCursorCapsMode(info.inputType);
        } catch (Throwable ignored) {
            candidateCapsMode = 0;
        }
        final String candidateBefore = readBefore(connection);
        final String candidateAfter = readAfter(connection);
        final EditorSnapshot candidateSnapshot = readSnapshot(connection);
        final float candidateSentencePauseSeconds = readSentencePauseSeconds();

        if (retryAvailable) {
            // The old retry audio cannot block a new tap now that Retry is not a control.
            // Confirm its recognized text is durable before retiring the audio queue.
            if (!retainedRecoveryText.isEmpty()) {
                PendingDictationDraft backup = pendingDraft == null
                        ? new PendingDictationDraft(activeSessionId, nextPieceSequence,
                                PendingDictationDraft.RETRYABLE, retainedRecoveryText)
                        : pendingDraft.preservingDeliveryRisk(PendingDictationDraft.RETRYABLE,
                                retainedRecoveryText, nextPieceSequence);
                if (!writeDraft(backup)) {
                    message = getString(R.string.voice_status_save_earlier_failed);
                    stateError = true;
                    publishState();
                    return false;
                }
                pendingDraft = backup;
            }
            if (!cancelRecording(activeSessionId)) {
                message = getString(R.string.voice_status_clear_audio_failed);
                stateError = true;
                publishState();
                return false;
            }
            retryAvailable = false;
        }

        boolean started;
        try {
            started = startRecording(candidateSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not start recording", error);
            started = false;
        }
        SessionDraftPolicy.StartResolution resolution = SessionDraftPolicy.afterStartAttempt(
                activeSessionId, retryAvailable, candidateSessionId, started);
        if (!resolution.replaced) {
            message = getString(R.string.voice_status_start_failed);
            stateError = true;
            publishState();
            return false;
        }

        activeSessionId = resolution.sessionId;
        nextPieceSequence = 0;
        targetEditor = editor;
        maySaveCurrentSession = host.maySaveDictation(info);
        targetFieldKind = candidateFieldKind;
        targetCapsMode = candidateCapsMode;
        expectedBefore = candidateBefore;
        expectedAfter = candidateAfter;
        expectedSelectionStart = selectionStart(candidateSnapshot);
        expectedSelectionEnd = selectionEnd(candidateSnapshot);
        sentencePauseSeconds = candidateSentencePauseSeconds;
        joiner.finish();
        hasAcceptedPiece = false;
        retryingSession = false;
        stateError = false;
        terminal = false;
        recording = true;
        retryAvailable = resolution.retryAvailable;
        autoDeliveryOpen = true;
        phase = Phase.RECORDING;
        message = getString(R.string.voice_status_listening);
        level = 0;
        undeliveredText = "";
        sessionCopyText = "";
        deferredPieces.clear();
        deferredCopyStart = -1;
        completionDeferred = false;
        sessionRecoveryBase = retainedRecoveryText;
        currentSessionNeedsRecovery = false;
        publishState();
        return true;
    }

    public boolean stop() {
        if (terminal || !host.isMainThread()) return false;
        recording = false;
        phase = Phase.FINISHING;
        message = getString(R.string.voice_status_finishing);
        boolean accepted;
        try {
            accepted = stopRecording(activeSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not stop recording", error);
            accepted = false;
        }
        publishState();
        return accepted;
    }

    /**
     * Queues the current audio piece while this recording and its microphone stay active.
     * Returns the audio boundary of this cut: every {@link #onTranscriptPiece} for audio before
     * the cut has a lower pieceSequence, every later one this value or a higher one, also when
     * the cut held no audio. -1 when nothing was cut.
     */
    public long transcribeNow() {
        if (terminal || !recording || !host.isMainThread()) return -1;
        try {
            return transcribeNowRecording(activeSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not transcribe current audio", error);
            message = getString(R.string.voice_status_transcribe_failed);
            stateError = true;
            publishState();
            return -1;
        }
    }

    public boolean cancel() {
        if (terminal || !host.isMainThread()) return false;
        recording = false;
        phase = Phase.FINISHING;
        message = getString(R.string.voice_status_canceling);
        boolean accepted;
        try {
            accepted = cancelRecording(activeSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not cancel recording", error);
            accepted = false;
        }
        publishState();
        return accepted;
    }

    public boolean retry() {
        if (!retryAvailable || !terminal || !host.isMainThread()) return false;
        boolean accepted;
        try {
            accepted = retryRecording(activeSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not retry inference", error);
            accepted = false;
        }
        if (accepted) {
            retryingSession = true;
            retryAvailable = false;
            terminal = false;
            phase = Phase.FINISHING;
            message = getString(R.string.voice_status_retrying);
        }
        publishState();
        return accepted;
    }

    /** Explicitly inserts the saved draft at the current cursor. */
    public boolean insertDraft() {
        if (!terminal || retainedRecoveryText.isEmpty() || !host.isMainThread()) return false;
        InputConnection connection = host.currentInputConnection();
        EditorInfo info = host.currentEditorInfo();
        if (!host.inputActive() || connection == null || info == null) {
            message = getString(R.string.voice_status_no_field);
            stateError = true;
            publishState();
            return false;
        }
        if (!prepareHostForVoiceCommit()) return false;
        int capsMode;
        try {
            capsMode = connection.getCursorCapsMode(info.inputType);
        } catch (Throwable ignored) {
            capsMode = 0;
        }
        String fitted = TextFitter.fit(retainedRecoveryText, readBefore(connection),
                readAfter(connection), FieldKinds.of(info), capsMode).inserted();
        if (fitted.isEmpty()) return false;

        PendingDictationDraft original = pendingDraft == null
                ? new PendingDictationDraft(activeSessionId, nextPieceSequence,
                        PendingDictationDraft.UNCERTAIN, retainedRecoveryText)
                : pendingDraft;
        PendingDictationDraft attempted = original.with(
                PendingDictationDraft.ATTEMPTED, fitted, original.nextSequence);
        if (!writeDraft(attempted)) {
            message = getString(R.string.voice_status_save_attempt_failed);
            publishState();
            return false;
        }
        pendingDraft = attempted;
        EditorSnapshot before = readSnapshot(connection);
        boolean accepted;
        try {
            accepted = connection.commitText(fitted, 1);
        } catch (Throwable error) {
            Log.w(TAG, "Explicit editor commit failed", error);
            accepted = false;
        }
        EditorSnapshot after = accepted ? readSnapshot(connection) : null;
        finishHostVoiceCommit();
        if (!accepted || before == null || after == null || !after.isExactCommitOf(before, fitted)) {
            retainedRecoveryText = fitted;
            message = getString(accepted ? R.string.voice_status_uncertain
                    : R.string.voice_status_rejected);
            publishState();
            return false;
        }
        if (!clearDraftFile()) {
            message = getString(R.string.voice_status_clear_copy_failed);
            publishState();
            return true;
        }
        retireRecovery();
        message = getString(R.string.voice_status_inserted);
        publishState();
        return true;
    }

    /** Retires copied recovery only after both clipboard and saved-draft cleanup succeed. */
    public boolean copyDraft() {
        if (!terminal || phase != Phase.IDLE || retainedRecoveryText.isEmpty()
                || !host.isMainThread()) return false;
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                message = getString(R.string.voice_status_copy_failed);
                stateError = true;
                publishState();
                return false;
            }
            clipboard.setPrimaryClip(ClipData.newPlainText(
                    getString(R.string.voice_clip_label), retainedRecoveryText));
            if (!clearDraftFile()) {
                message = getString(R.string.voice_status_copy_retire_failed);
                stateError = true;
                publishState();
                return false;
            }
            retireRecovery();
            message = getString(R.string.voice_status_copied);
            stateError = false;
            publishState();
            return true;
        } catch (Throwable error) {
            Log.w(TAG, "Clipboard write failed", error);
            message = getString(R.string.voice_status_copy_failed);
            stateError = true;
            publishState();
            return false;
        }
    }

    /** Discards saved recovery and any retry audio after native cancellation accepts it. */
    public boolean discardDraft() {
        final boolean hasDraft = !retainedRecoveryText.isEmpty();
        if (!host.isMainThread()
                || !SessionDraftPolicy.canDiscard(hasDraft, draftReadError, retryAvailable)) {
            return false;
        }
        if (retryAvailable) {
            boolean cancelled;
            try {
                cancelled = cancelRecording(activeSessionId);
            } catch (Throwable error) {
                Log.e(TAG, "Could not discard retry audio", error);
                cancelled = false;
            }
            if (!cancelled) {
                message = getString(R.string.voice_status_discard_audio_failed);
                publishState();
                return false;
            }
            retryAvailable = false;
        }
        if (!clearDraftFile()) {
            message = getString(R.string.voice_status_discard_failed);
            publishState();
            return false;
        }
        retireRecovery();
        message = getString(hasDraft ? R.string.voice_status_discarded
                : R.string.voice_status_audio_discarded);
        publishState();
        return true;
    }

    @Override public void close() {
        if (!initialized) return;
        try {
            cleanupNative();
        } catch (Throwable error) {
            Log.w(TAG, "Native cleanup failed", error);
        }
        initialized = false;
        recording = false;
        terminal = true;
        phase = Phase.IDLE;
    }

    // JNI callbacks. Names and signatures match frozen voice_session.rs exactly.

    public void onStatusUpdate(String status) {
        onMain(() -> {
            if (status != null && !status.isEmpty()) Log.i(TAG, "Native status: " + status);
            if (!initialized && terminal) message = getString(status != null
                    && status.startsWith("Error:") ? R.string.voice_status_unavailable
                    : R.string.voice_status_loading);
            publishState();
        });
    }

    public void onDictationStatus(long sessionId, String status) {
        onMain(() -> {
            if (sessionId != activeSessionId || terminal) return;
            if (status != null && !status.isEmpty()) Log.i(TAG, "Native dictation status: " + status);
            final int label = "Listening...".equals(status) ? R.string.voice_status_listening
                    : "Transcribing...".equals(status) ? R.string.voice_status_transcribing
                    : "Retrying...".equals(status) ? R.string.voice_status_retrying
                    : status != null && status.startsWith("Error:") ? R.string.voice_status_failed
                    : phase == Phase.RECORDING ? R.string.voice_status_listening
                    : R.string.voice_status_transcribing;
            message = getString(label);
            publishState();
        });
    }

    public void onDictationLevel(long sessionId, float newLevel) {
        onMain(() -> {
            if (sessionId != activeSessionId) return;
            level = newLevel;
        });
    }

    public void onAutoStop(long sessionId) {
        onMain(() -> {
            if (sessionId != activeSessionId || terminal || !recording) return;
            stop();
        });
    }

    public boolean onTranscriptPiece(long sessionId, long pieceSequence,
                                     String text, float pauseBeforeSeconds) {
        if (host.isMainThread()) {
            return acceptPiece(sessionId, pieceSequence, text, pauseBeforeSeconds);
        }
        final boolean[] accepted = {false};
        CountDownLatch done = new CountDownLatch(1);
        try {
            host.postToMain(() -> {
                try {
                    accepted[0] = acceptPiece(
                            sessionId, pieceSequence, text, pauseBeforeSeconds);
                } finally {
                    done.countDown();
                }
            });
            return done.await(15, TimeUnit.SECONDS) && accepted[0];
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable error) {
            Log.e(TAG, "Could not dispatch transcript piece", error);
            return false;
        }
    }

    public void onDictationComplete(long sessionId, int outcome, String text, String error) {
        onMain(() -> finishSession(sessionId, outcome, text, error));
    }

    private boolean acceptPiece(long sessionId, long pieceSequence,
                                String text, float pauseBeforeSeconds) {
        if (sessionId != activeSessionId || terminal || text == null
                || text.trim().isEmpty()) return false;
        if (pieceSequence < nextPieceSequence) return true;
        if (!host.voiceCommitReady() || !deferredPieces.isEmpty()) {
            // A swipe can change both cursor position and text before the cursor.
            // Save the raw words before acknowledging native; format them only after
            // the gesture result has reached the editor.
            String previousCopy = sessionCopyText;
            String raw = text.trim();
            sessionCopyText += sessionCopyText.isEmpty()
                    || Character.isWhitespace(sessionCopyText.charAt(sessionCopyText.length() - 1))
                    ? raw : " " + raw;
            String saved = RecoveryText.joinRecords(sessionRecoveryBase,
                    sessionCopyText);
            PendingDictationDraft staged = new PendingDictationDraft(sessionId,
                    pieceSequence + 1, sessionRecoveryBase.isEmpty()
                    ? PendingDictationDraft.PENDING : PendingDictationDraft.UNCERTAIN, saved);
            if (!writeDraft(staged)) {
                sessionCopyText = previousCopy;
                message = getString(R.string.voice_status_save_text_failed);
                stateError = true;
                publishState();
                return false;
            }
            if (deferredPieces.isEmpty()) deferredCopyStart = previousCopy.length();
            deferredPieces.add(new DeferredPiece(raw, pauseBeforeSeconds));
            pendingDraft = staged;
            nextPieceSequence = pieceSequence + 1;
            if (host.voiceCommitReady()) deliverStagedText();
            publishState();
            return true;
        }
        InputConnection connection = currentTargetConnection();
        if (connection != null && host.voiceCommitReady()) reconcileContinuity(connection);

        String oldTail = joiner.pendingTail();
        int capsMode = PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece);
        CharSequence ownedBefore = expectedBefore;
        if (expectedBefore != null && !undeliveredText.isEmpty()) {
            ownedBefore = expectedBefore + undeliveredText;
        }
        String candidate = joiner.join(text, pauseBeforeSeconds, ownedBefore, expectedAfter,
                targetFieldKind, capsMode, sentencePauseSeconds);
        String stagedCurrent = sessionCopyText + candidate + joiner.pendingTail();
        String stagedText = RecoveryText.joinRecords(sessionRecoveryBase, stagedCurrent);
        PendingDictationDraft staged = new PendingDictationDraft(
                sessionId, pieceSequence + 1,
                sessionRecoveryBase.isEmpty() ? PendingDictationDraft.PENDING
                        : PendingDictationDraft.UNCERTAIN,
                stagedText);
        if (!writeDraft(staged)) {
            joiner.restorePendingTail(oldTail);
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            publishState();
            return false;
        }
        pendingDraft = staged;
        nextPieceSequence = pieceSequence + 1;
        hasAcceptedPiece = true;
        undeliveredText += candidate;
        sessionCopyText += candidate;
        if (!undeliveredText.isEmpty()) deliverStagedText();
        publishState();
        return true;
    }

    private void finishSession(long sessionId, int outcome, String text, String error) {
        if (sessionId != activeSessionId || terminal) return;
        if (outcome == OUTCOME_SUCCESS && !deferredPieces.isEmpty()) {
            if (host.voiceCommitReady()) deliverStagedText();
            if (!deferredPieces.isEmpty()) {
                if (!host.voiceCommitReady()) {
                    completionDeferred = true;
                    deferredOutcome = outcome;
                    deferredFinalText = text;
                    deferredError = error;
                    recording = false;
                    phase = Phase.FINISHING;
                    message = getString(R.string.voice_status_finishing);
                    publishState();
                    return;
                }
                // A write or editor preparation failed after the gesture. The raw
                // transcript is already durable, so finish with a recoverable error.
                retainCurrentSession(PendingDictationDraft.UNCERTAIN);
                deferredPieces.clear();
                deferredCopyStart = -1;
                outcome = OUTCOME_INTERRUPTED;
                error = "Could not deliver dictated text";
            }
        }
        if (outcome != OUTCOME_SUCCESS && !deferredPieces.isEmpty()) {
            deferredPieces.clear();
            deferredCopyStart = -1;
        }
        stateError = false;
        terminal = true;
        recording = false;
        phase = Phase.IDLE;
        level = 0;

        if (outcome == OUTCOME_SUCCESS) {
            InputConnection connection = currentTargetConnection();
            boolean abandonedTail = connection != null && host.voiceCommitReady()
                    && reconcileContinuity(connection);
            if (abandonedTail && undeliveredText.isEmpty()) settleDelivery(false, null);
            String tail = joiner.finish();
            if (!tail.isEmpty()) {
                undeliveredText += tail;
                sessionCopyText += tail;
                PendingDictationDraft staged = new PendingDictationDraft(
                        sessionId, nextPieceSequence,
                        sessionRecoveryBase.isEmpty() ? PendingDictationDraft.PENDING
                                : PendingDictationDraft.UNCERTAIN,
                        RecoveryText.joinRecords(sessionRecoveryBase, sessionCopyText));
                if (writeDraft(staged)) {
                    pendingDraft = staged;
                } else {
                    pendingDraft = staged;
                    autoDeliveryOpen = false;
                    undeliveredText = "";
                    currentSessionNeedsRecovery = true;
                    retainedRecoveryText = staged.text;
                    message = getString(R.string.voice_status_save_punctuation_failed);
                }
            }
            if (!undeliveredText.isEmpty()) deliverStagedText();
            if (message == null || message.isEmpty()) message = getString(R.string.voice_status_complete);
        } else if (outcome == OUTCOME_REVIEW) {
            joiner.finish();
            autoDeliveryOpen = false;
            undeliveredText = "";
            retainedRecoveryText = RecoveryText.joinRecords(
                    sessionRecoveryBase, text == null ? "" : text);
            currentSessionNeedsRecovery = !retainedRecoveryText.isEmpty();
            PendingDictationDraft review = new PendingDictationDraft(
                    sessionId, nextPieceSequence, PendingDictationDraft.REVIEW,
                    retainedRecoveryText);
            writeDraft(review);
            pendingDraft = review;
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native review: " + error);
            message = getString(R.string.voice_status_review);
            stateError = true;
        } else if (outcome == OUTCOME_RETRYABLE) {
            retryAvailable = true;
            if (!sessionCopyText.isEmpty()) retainCurrentSession(PendingDictationDraft.RETRYABLE);
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native retryable failure: " + error);
            message = getString(R.string.voice_status_failed);
            stateError = true;
        } else if (outcome == OUTCOME_CANCELLED) {
            joiner.finish();
            message = getString(R.string.voice_status_canceled);
        } else {
            String recovery = pendingDraft == null ? undeliveredText : pendingDraft.text;
            if (!recovery.isEmpty()) {
                retainedRecoveryText = recovery;
                currentSessionNeedsRecovery = true;
                PendingDictationDraft interrupted = pendingDraft == null
                        ? new PendingDictationDraft(sessionId, nextPieceSequence,
                                PendingDictationDraft.INTERRUPTED, recovery)
                        : pendingDraft.preservingDeliveryRisk(
                                PendingDictationDraft.INTERRUPTED, recovery, nextPieceSequence);
                writeDraft(interrupted);
                pendingDraft = interrupted;
            }
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native failure: " + error);
            message = getString(outcome == OUTCOME_INTERRUPTED
                    ? R.string.voice_status_interrupted : R.string.voice_status_failed);
            stateError = true;
        }
        if (maySaveCurrentSession) {
            // Native sends the full revised candidate for REVIEW, and the full replay on retry.
            String historyText = (outcome == OUTCOME_REVIEW || retryingSession
                    || sessionCopyText.isBlank()) && text != null && !text.isBlank()
                    ? text : sessionCopyText;
            if (!historyText.isBlank() && !host.saveDictation(sessionId, historyText)) {
                String backupText = currentSessionNeedsRecovery ? retainedRecoveryText
                        : RecoveryText.joinRecords(retainedRecoveryText, historyText);
                PendingDictationDraft backup = new PendingDictationDraft(sessionId,
                        nextPieceSequence, PendingDictationDraft.UNCERTAIN, backupText);
                if (writeDraft(backup)) {
                    pendingDraft = backup;
                    retainedRecoveryText = backupText;
                    currentSessionNeedsRecovery = true;
                    message = getString(R.string.voice_status_history_backup);
                } else {
                    message = getString(R.string.voice_status_history_failed);
                }
                stateError = true;
            }
        }
        publishState();
    }

    private void deliverStagedText() {
        if ((undeliveredText.isEmpty() && deferredPieces.isEmpty()) || pendingDraft == null) return;
        SessionDraftPolicy.Delivery decision = SessionDraftPolicy.automatic(
                targetEditor, host.currentEditorIdentity(), host.inputActive(), autoDeliveryOpen);
        InputConnection connection = decision == SessionDraftPolicy.Delivery.INSERT
                ? host.currentInputConnection() : null;
        if (connection == null) {
            autoDeliveryOpen = false;
            retainCurrentSession(PendingDictationDraft.UNCERTAIN);
            undeliveredText = "";
            deferredPieces.clear();
            deferredCopyStart = -1;
            message = getString(R.string.voice_status_field_changed);
            return;
        }

        if (!host.voiceCommitReady()) {
            Log.i(TAG, "Voice delivery deferred for active gesture");
            return;
        }
        if (!prepareHostForVoiceCommit()) {
            message = getString(R.string.voice_status_editor_failed);
            stateError = true;
            return;
        }
        if (!deferredPieces.isEmpty()) {
            reconcileContinuity(connection);
            if (!stageDeferredPieces()) return;
        }
        refreshContext(connection);
        EditorSnapshot before = readSnapshot(connection);
        PendingDictationDraft attempted = pendingDraft.with(
                PendingDictationDraft.ATTEMPTED, pendingDraft.text, nextPieceSequence);
        if (!writeDraft(attempted)) {
            message = getString(R.string.voice_status_save_delivery_failed);
            return;
        }
        pendingDraft = attempted;
        String sent = undeliveredText;
        boolean accepted;
        try {
            accepted = connection.commitText(sent, 1);
        } catch (Throwable error) {
            Log.w(TAG, "Editor commit failed", error);
            accepted = false;
        }
        EditorSnapshot after = accepted ? readSnapshot(connection) : null;
        finishHostVoiceCommit();
        if (!accepted || before == null || after == null || !after.isExactCommitOf(before, sent)) {
            autoDeliveryOpen = SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(accepted);
            settleDelivery(true, getString(accepted
                    ? R.string.voice_status_delivery_uncertain
                    : R.string.voice_status_delivery_rejected));
            return;
        }
        refreshContext(connection, after);
        settleDelivery(false, null);
    }

    /** Retries an already staged chunk once a swipe has been committed or canceled. */
    public void resumePendingDelivery() {
        if (!host.isMainThread()
                || (undeliveredText.isEmpty() && deferredPieces.isEmpty())) return;
        Log.i(TAG, "Resuming pending voice delivery");
        deliverStagedText();
        if (completionDeferred && host.voiceCommitReady()) {
            completionDeferred = false;
            finishSession(activeSessionId, deferredOutcome, deferredFinalText, deferredError);
        } else {
            publishState();
        }
    }

    /** Replays saved raw pieces against the cursor after the swipe, before any commit. */
    private boolean stageDeferredPieces() {
        String rawCopy = sessionCopyText;
        String oldTail = joiner.pendingTail();
        boolean oldAccepted = hasAcceptedPiece;
        sessionCopyText = rawCopy.substring(0, deferredCopyStart);
        StringBuilder rendered = new StringBuilder();
        for (DeferredPiece piece : deferredPieces) {
            CharSequence before = expectedBefore;
            if (before != null) before = before.toString() + undeliveredText + rendered;
            String candidate = joiner.join(piece.text, piece.pauseBeforeSeconds,
                    before, expectedAfter, targetFieldKind,
                    PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece),
                    sentencePauseSeconds);
            rendered.append(candidate);
            sessionCopyText += candidate;
            hasAcceptedPiece = true;
        }
        PendingDictationDraft staged = new PendingDictationDraft(activeSessionId,
                nextPieceSequence, sessionRecoveryBase.isEmpty()
                ? PendingDictationDraft.PENDING : PendingDictationDraft.UNCERTAIN,
                RecoveryText.joinRecords(sessionRecoveryBase,
                        sessionCopyText + joiner.pendingTail()));
        if (!writeDraft(staged)) {
            sessionCopyText = rawCopy;
            joiner.restorePendingTail(oldTail);
            hasAcceptedPiece = oldAccepted;
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            return false;
        }
        pendingDraft = staged;
        undeliveredText += rendered;
        deferredPieces.clear();
        deferredCopyStart = -1;
        return true;
    }

    private void settleDelivery(boolean retainCopy, String newMessage) {
        if (retainCopy) currentSessionNeedsRecovery = true;
        retainedRecoveryText = RecoveryText.settled(
                sessionRecoveryBase, sessionCopyText, currentSessionNeedsRecovery);
        undeliveredText = "";
        String saved = RecoveryText.joinRecords(retainedRecoveryText, joiner.pendingTail());
        if (saved.isEmpty()) {
            clearDraftFile();
        } else {
            PendingDictationDraft recovery = new PendingDictationDraft(
                    activeSessionId, nextPieceSequence,
                    retainedRecoveryText.isEmpty() ? PendingDictationDraft.PENDING
                            : PendingDictationDraft.UNCERTAIN,
                    saved);
            writeDraft(recovery);
            pendingDraft = recovery;
        }
        if (newMessage != null) message = newMessage;
    }

    private void retainCurrentSession(String requestedState) {
        currentSessionNeedsRecovery = true;
        retainedRecoveryText = RecoveryText.joinRecords(sessionRecoveryBase, sessionCopyText);
        PendingDictationDraft recovery = pendingDraft != null
                && pendingDraft.sessionId == activeSessionId
                ? pendingDraft.preservingDeliveryRisk(
                        requestedState, retainedRecoveryText, nextPieceSequence)
                : new PendingDictationDraft(
                        activeSessionId, nextPieceSequence, requestedState, retainedRecoveryText);
        writeDraft(recovery);
        pendingDraft = recovery;
    }

    private InputConnection currentTargetConnection() {
        return SessionDraftPolicy.automatic(targetEditor, host.currentEditorIdentity(),
                host.inputActive(), autoDeliveryOpen) == SessionDraftPolicy.Delivery.INSERT
                ? host.currentInputConnection() : null;
    }

    private boolean reconcileContinuity(InputConnection connection) {
        String before = readBefore(connection);
        String after = readAfter(connection);
        EditorSnapshot snapshot = readSnapshot(connection);
        boolean changed = SessionDraftPolicy.contextChanged(
                expectedBefore, expectedAfter, expectedSelectionStart, expectedSelectionEnd,
                before, after, selectionStart(snapshot), selectionEnd(snapshot));
        boolean abandonedTail = false;
        if (changed) {
            abandonedTail = !joiner.abandonHeldTail().isEmpty();
            hasAcceptedPiece = false;
            EditorInfo info = host.currentEditorInfo();
            try {
                targetCapsMode = info == null ? 0 : connection.getCursorCapsMode(info.inputType);
            } catch (Throwable ignored) {
                targetCapsMode = 0;
            }
        }
        expectedBefore = before;
        expectedAfter = after;
        expectedSelectionStart = selectionStart(snapshot);
        expectedSelectionEnd = selectionEnd(snapshot);
        return abandonedTail;
    }

    private void refreshContext(InputConnection connection) {
        refreshContext(connection, readSnapshot(connection));
    }

    private void refreshContext(InputConnection connection, EditorSnapshot snapshot) {
        String before = readBefore(connection);
        String after = readAfter(connection);
        if (before != null) expectedBefore = before;
        if (after != null) expectedAfter = after;
        int selectionStart = selectionStart(snapshot);
        int selectionEnd = selectionEnd(snapshot);
        if (selectionStart >= 0) expectedSelectionStart = selectionStart;
        if (selectionEnd >= 0) expectedSelectionEnd = selectionEnd;
    }

    private boolean prepareHostForVoiceCommit() {
        if (!host.voiceCommitReady()) return false;
        try {
            return host.prepareForVoiceCommit();
        } catch (Throwable error) {
            Log.w(TAG, "Could not prepare HeliBoard for voice text", error);
            return false;
        }
    }

    private void finishHostVoiceCommit() {
        try {
            host.finishVoiceCommit();
        } catch (Throwable error) {
            Log.w(TAG, "Could not refresh HeliBoard after voice text", error);
        }
    }

    private static int selectionStart(EditorSnapshot snapshot) {
        return snapshot == null ? -1 : snapshot.startOffset + snapshot.selectionStart;
    }

    private static int selectionEnd(EditorSnapshot snapshot) {
        return snapshot == null ? -1 : snapshot.startOffset + snapshot.selectionEnd;
    }

    private void restoreDraft() {
        try {
            PendingDictationDraft restored = PendingDictationDraft.decode(draftFile.readFully());
            if (restored == null) {
                draftReadError = true;
                message = getString(R.string.voice_status_saved_damaged);
                stateError = true;
                return;
            }
            if (PendingDictationDraft.PENDING.equals(restored.state)
                    || PendingDictationDraft.RETRYABLE.equals(restored.state)) {
                restored = restored.with(PendingDictationDraft.INTERRUPTED,
                        restored.text, restored.nextSequence);
                writeDraft(restored);
            }
            pendingDraft = restored;
            retainedRecoveryText = restored.text;
            activeSessionId = restored.sessionId;
            nextPieceSequence = restored.nextSequence;
            message = getString(R.string.voice_status_saved_available);
        } catch (FileNotFoundException ignored) {
            // First run.
        } catch (Throwable error) {
            Log.e(TAG, "Could not restore pending dictation", error);
            draftReadError = true;
            message = getString(R.string.voice_status_saved_unreadable);
            stateError = true;
        }
    }

    private boolean writeDraft(PendingDictationDraft draft) {
        FileOutputStream output = null;
        try {
            output = draftFile.startWrite();
            output.write(draft.encode());
            draftFile.finishWrite(output);
            output = null;
            PendingDictationDraft check = PendingDictationDraft.decode(draftFile.readFully());
            boolean matches = check != null && check.sessionId == draft.sessionId
                    && check.nextSequence == draft.nextSequence
                    && check.state.equals(draft.state) && check.text.equals(draft.text);
            if (matches) draftReadError = false;
            return matches;
        } catch (Throwable error) {
            if (output != null) draftFile.failWrite(output);
            Log.e(TAG, "Could not persist pending dictation", error);
            return false;
        }
    }

    private boolean clearDraftFile() {
        try {
            draftFile.delete();
        } catch (Throwable error) {
            Log.e(TAG, "Could not delete pending dictation", error);
        }
        File base = draftFile.getBaseFile();
        boolean cleared = !base.exists() && !new File(base.getPath() + ".bak").exists()
                && !new File(base.getPath() + ".new").exists();
        if (cleared) {
            pendingDraft = null;
            draftReadError = false;
        } else {
            draftReadError = true;
        }
        return cleared;
    }

    private void retireRecovery() {
        pendingDraft = null;
        retainedRecoveryText = "";
        sessionRecoveryBase = "";
        sessionCopyText = "";
        currentSessionNeedsRecovery = false;
    }

    private float readSentencePauseSeconds() {
        File file = new File(getFilesDir(), SETTING_SENTENCE_PAUSE_SECONDS);
        if (!file.exists()) return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            return SentencePauseSetting.parse(reader.readLine());
        } catch (Throwable error) {
            Log.w(TAG, "Could not read sentence pause setting", error);
            return PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;
        }
    }

    private String readBefore(InputConnection connection) {
        if (connection == null) return null;
        try {
            CharSequence value = connection.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readAfter(InputConnection connection) {
        if (connection == null) return null;
        try {
            CharSequence value = connection.getTextAfterCursor(CONTEXT_AFTER_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private EditorSnapshot readSnapshot(InputConnection connection) {
        try {
            ExtractedTextRequest request = new ExtractedTextRequest();
            request.hintMaxChars = 1024 * 1024;
            ExtractedText extracted = connection.getExtractedText(request, 0);
            if (extracted == null || extracted.text == null) return null;
            return new EditorSnapshot(extracted.text.toString(), extracted.startOffset,
                    extracted.selectionStart, extracted.selectionEnd);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private void onMain(Runnable action) {
        if (host.isMainThread()) action.run(); else host.postToMain(action);
    }

    private void publishState() {
        boolean hasDraft = !retainedRecoveryText.isEmpty();
        host.postVoiceState(new VoiceState(phase, message, level,
                hasDraft && terminal, hasDraft && terminal,
                SessionDraftPolicy.canDiscard(hasDraft, draftReadError, retryAvailable),
                retryAvailable && terminal, stateError));
    }

    private static final class EditorSnapshot {
        final String text;
        final int startOffset;
        final int selectionStart;
        final int selectionEnd;

        EditorSnapshot(String text, int startOffset, int selectionStart, int selectionEnd) {
            this.text = text;
            this.startOffset = startOffset;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
        }

        boolean isExactCommitOf(EditorSnapshot before, String inserted) {
            if (before == null || startOffset != before.startOffset
                    || !valid() || !before.valid()) return false;
            int start = Math.min(before.selectionStart, before.selectionEnd);
            int end = Math.max(before.selectionStart, before.selectionEnd);
            String expected = before.text.substring(0, start) + inserted
                    + before.text.substring(end);
            int cursor = start + inserted.length();
            return expected.equals(text) && selectionStart == cursor && selectionEnd == cursor;
        }

        private boolean valid() {
            int start = Math.min(selectionStart, selectionEnd);
            int end = Math.max(selectionStart, selectionEnd);
            return start >= 0 && end <= text.length();
        }
    }

    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native boolean startRecording(long sessionId);
    private native boolean stopRecording(long sessionId);
    private native long transcribeNowRecording(long sessionId);
    private native boolean cancelRecording(long sessionId);
    private native boolean retryRecording(long sessionId);
}
