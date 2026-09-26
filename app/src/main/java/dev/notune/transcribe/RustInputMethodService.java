package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Build;
import android.os.PersistableBundle;
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
import java.util.Iterator;
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
 *
 * <p>Editor entry points (seam S1): HeliBoard calls {@link #onEditorStarted} after each
 * {@code onStartInput} and {@link #onInputViewFinished} when the keyboard view finishes.
 * Each recording is one {@link VoiceInterval} bound to an {@link EditorRecord} and a
 * binding generation; earlier intervals that still need recovery keep their own.
 */
public final class RustInputMethodService extends ContextWrapper implements AutoCloseable {
    private static final String TAG = "NoTuneVoice";
    private static final String DRAFT_FILE = "pending-dictation";
    private static final int CONTEXT_BEFORE_CHARS = 2048;
    private static final int CONTEXT_AFTER_CHARS = 512;
    /** Earlier results kept for Copy; the oldest goes when a fourth arrives. */
    private static final int MAX_RECOVERIES = 3;
    private static final long NOTICE_MILLIS = 3000;

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

        /** The record made at the last {@code onStartInput}; null after {@code onFinishInput}. */
        EditorRecord currentEditor();

        boolean isMainThread();
        void postToMain(Runnable action);
        void postToMainDelayed(Runnable action, long delayMillis);

        /** Commits HeliBoard's active composing word before voice writes to the editor. */
        boolean prepareForVoiceCommit();

        /** False from touch-down on the keyboard until a swipe result has reached the editor. */
        boolean voiceCommitReady();

        /** True when HeliBoard owes a space after its last word (a swipe's phantom space). */
        boolean spacePendingBeforeVoice();

        /** Reloads HeliBoard's editor state after the controller has attempted a write. */
        void finishVoiceCommit();

        /** Must enqueue or render state without opening a modal surface. */
        void postVoiceState(VoiceState state);

        /** Optional history only; never gates insertion, the draft or Copy. */
        boolean maySaveDictation(EditorRecord editor);

        /** Writes or updates one history item for this recording. */
        boolean saveDictation(long sessionId, String text);
    }

    public enum Phase { IDLE, RECORDING, FINISHING }

    /** Immutable state for HeliBoard's existing toolbar and nonblocking recovery controls. */
    public static final class VoiceState {
        public final Phase phase;
        public final String message;
        public final float level;
        public final boolean canCopy;
        public final boolean error;
        /** A short information line, such as "Nothing heard"; not a failure. */
        public final boolean notice;

        VoiceState(Phase phase, String message, float level, boolean canCopy,
                   boolean error, boolean notice) {
            this.phase = phase;
            this.message = message;
            this.level = level;
            this.canCopy = canCopy;
            this.error = error;
            this.notice = notice;
        }
    }

    private final Host host;
    private final AtomicFile draftFile;
    private final PieceJoiner joiner = new PieceJoiner();

    private boolean initialized;
    private boolean terminal = true;
    private boolean recording;
    private boolean stateError;
    private boolean notice;
    private long noticeToken;
    private boolean autoDeliveryOpen;
    private Phase phase = Phase.IDLE;
    private String message;
    private float level;

    private long activeSessionId;
    private long nextPieceSequence;
    /** Increases at every started field that does not continue the previous binding. */
    private long bindingGeneration = 1;
    private VoiceInterval session;
    private boolean targetLost;
    private String expectedBefore;
    private String expectedAfter;
    private int expectedSelectionStart = -1;
    private int expectedSelectionEnd = -1;
    private int targetCapsMode;
    private boolean hasAcceptedPiece;
    private boolean leadSpacePending;
    private boolean numberRefused;
    private float sentencePauseSeconds = PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS;

    private String undeliveredText = "";
    /** The model's words as spoken, for a number field that could not take them. */
    private String sessionRawText = "";
    private static final class DeferredPiece {
        final String text;
        final float pauseBeforeSeconds;

        DeferredPiece(String text, float pauseBeforeSeconds) {
            this.text = text;
            this.pauseBeforeSeconds = pauseBeforeSeconds;
        }
    }
    private final ArrayList<DeferredPiece> deferredPieces = new ArrayList<>();
    /** The finished session already moved (or not) into the Copy list. */
    private VoiceInterval settledSession;
    private int deferredCopyStart = -1;
    private boolean completionDeferred;
    private int deferredOutcome;
    private String deferredFinalText;
    private String deferredError;
    private boolean maySaveCurrentSession;
    /** Earlier results that still need Copy, oldest first; never joined together. */
    private final ArrayList<VoiceInterval> recoveries = new ArrayList<>();

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

    /** Starts one recording bound to the current editor record and binding. */
    public boolean start() {
        if (!initialized || !terminal || !host.isMainThread()) return false;
        InputConnection connection = host.currentInputConnection();
        EditorInfo info = host.currentEditorInfo();
        EditorRecord editor = host.currentEditor();
        if (connection == null || info == null || editor == null) {
            message = getString(R.string.voice_status_no_field);
            stateError = true;
            publishState();
            return false;
        }
        // Text of the previous recording that is still waiting for its field becomes a
        // recovery item of its own; it is never joined into the new recording.
        abandonStagedText();
        final boolean candidateLeadSpace = !editor.readBackKnown && host.spacePendingBeforeVoice();
        // Some editors reject HeliBoard composition but still accept a direct commit.
        if (!prepareHostForVoiceCommit()) {
            message = getString(R.string.voice_status_finish_gesture);
            stateError = true;
            publishState();
            return false;
        }

        final long candidateSessionId = Math.max(
                activeSessionId + 1, Math.max(1, System.nanoTime()));
        int candidateCapsMode;
        try {
            candidateCapsMode = connection.getCursorCapsMode(info.inputType);
        } catch (Throwable ignored) {
            candidateCapsMode = 0;
        }
        final String candidateBefore = readBefore(connection, editor);
        final String candidateAfter = readAfter(connection, editor);
        final EditorSnapshot candidateSnapshot = readSnapshot(connection, editor);
        final float candidateSentencePauseSeconds = readSentencePauseSeconds();

        boolean started;
        try {
            started = startRecording(candidateSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not start recording", error);
            started = false;
        }
        if (!started) {
            message = getString(R.string.voice_status_start_failed);
            stateError = true;
            publishState();
            return false;
        }

        activeSessionId = candidateSessionId;
        nextPieceSequence = 0;
        session = new VoiceInterval(candidateSessionId, editor, bindingGeneration);
        targetLost = false;
        maySaveCurrentSession = host.maySaveDictation(editor);
        targetCapsMode = candidateCapsMode;
        expectedBefore = candidateBefore;
        expectedAfter = candidateAfter;
        expectedSelectionStart = selectionStart(candidateSnapshot);
        expectedSelectionEnd = selectionEnd(candidateSnapshot);
        sentencePauseSeconds = candidateSentencePauseSeconds;
        joiner.finish();
        hasAcceptedPiece = false;
        leadSpacePending = candidateLeadSpace;
        numberRefused = false;
        stateError = false;
        clearNotice();
        terminal = false;
        recording = true;
        autoDeliveryOpen = true;
        phase = Phase.RECORDING;
        message = getString(R.string.voice_status_listening);
        level = 0;
        undeliveredText = "";
        sessionRawText = "";
        deferredPieces.clear();
        deferredCopyStart = -1;
        completionDeferred = false;
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

    /** Queues the current audio piece while this recording and its microphone stay active. */
    public boolean transcribeNow() {
        if (terminal || !recording || !host.isMainThread()) return false;
        try {
            return transcribeNowRecording(activeSessionId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not transcribe current audio", error);
            message = getString(R.string.voice_status_transcribe_failed);
            stateError = true;
            publishState();
            return false;
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

    /** Copies the one result offered here; a successful copy consumes it and hides Copy. */
    public boolean copyDraft() {
        if (!terminal || phase != Phase.IDLE || !host.isMainThread()) return false;
        VoiceInterval item = offeredRecovery();
        if (item == null) return false;
        try {
            ClipboardManager clipboard =
                    (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                message = getString(R.string.voice_status_copy_failed);
                stateError = true;
                publishState();
                return false;
            }
            ClipData clip = ClipData.newPlainText(getString(R.string.voice_clip_label), item.text);
            if (item.privateOrigin() && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Words from a private field stay out of clipboard previews and history.
                PersistableBundle extras = new PersistableBundle();
                extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
                clip.getDescription().setExtras(extras);
            }
            clipboard.setPrimaryClip(clip);
        } catch (Throwable error) {
            Log.w(TAG, "Clipboard write failed", error);
            message = getString(R.string.voice_status_copy_failed);
            stateError = true;
            publishState();
            return false;
        }
        int index = recoveries.indexOf(item);
        recoveries.remove(index);
        if (!persist()) {
            // The saved copy is still on disk: keep offering it rather than claim it is gone.
            recoveries.add(index, item);
            message = getString(R.string.voice_status_copy_retire_failed);
            stateError = true;
            publishState();
            return false;
        }
        message = getString(R.string.voice_status_copied);
        stateError = false;
        publishState();
        return true;
    }

    /**
     * Call after every {@code onStartInput}. A field continues the current binding only
     * when it is the same field and either the keyboard is rotating or the text around the
     * cursor reads exactly as voice left it. Any other field starts a new binding: a
     * recording for the old one stops, and its undelivered words go to Copy.
     */
    public void onEditorStarted(boolean rotating) {
        if (!host.isMainThread()) return;
        EditorRecord current = host.currentEditor();
        if (current != null && current.noField) {
            // Home shows the launcher, which has no field: wait for the next real field.
            publishState();
            return;
        }
        boolean continues = false;
        if (session != null && !targetLost && current != null) {
            InputConnection connection = host.currentInputConnection();
            EditorSnapshot snapshot = rotating ? null : readSnapshot(connection, current);
            String before = rotating ? null : readBefore(connection, current);
            String after = rotating ? null : readAfter(connection, current);
            continues = SessionDraftPolicy.continuesBinding(session.destination, current, rotating,
                    expectedBefore, expectedAfter, expectedSelectionStart, expectedSelectionEnd,
                    before, after, selectionStart(snapshot), selectionEnd(snapshot));
            if (!continues) {
                // Lengths and flags only: the text itself stays out of the log.
                Log.i(TAG, "Voice binding ends: same field " + session.destination.sameField(current)
                        + ", before " + length(expectedBefore) + "/" + length(before)
                        + " equal " + java.util.Objects.equals(expectedBefore, before)
                        + ", after " + length(expectedAfter) + "/" + length(after)
                        + " equal " + java.util.Objects.equals(expectedAfter, after)
                        + ", selection " + expectedSelectionStart + "," + expectedSelectionEnd
                        + "/" + selectionStart(snapshot) + "," + selectionEnd(snapshot));
            }
        }
        if (continues) {
            Log.i(TAG, "Voice binding continues in " + current + (rotating ? " (rotation)" : ""));
            resumePendingDelivery();
            return;
        }
        bindingGeneration++;
        for (Iterator<VoiceInterval> it = recoveries.iterator(); it.hasNext(); ) {
            VoiceInterval item = it.next();
            // Unconfirmed text is offered only until the user leaves its field.
            if (item.state == VoiceInterval.State.UNCONFIRMED) it.remove();
        }
        if (session != null && !targetLost && (!terminal || hasStagedText())) {
            Log.i(TAG, "Voice target left; words kept for Copy");
            loseTarget();
        }
        publishState();
    }

    /**
     * Call after the user's own edit in the current field (a typed key or a swipe word).
     * Words whose delivery is uncertain may already be in the field; once the user edits,
     * a Copy of them would duplicate or mislead, so it ends. Undelivered words stay.
     */
    public void onUserEdit() {
        if (!host.isMainThread()) return;
        boolean removed = false;
        for (Iterator<VoiceInterval> it = recoveries.iterator(); it.hasNext(); ) {
            VoiceInterval item = it.next();
            if (item.state() == VoiceInterval.State.UNCONFIRMED
                    && item.binding == bindingGeneration) {
                it.remove();
                removed = true;
            }
        }
        if (removed) {
            Log.i(TAG, "User edited the field; unconfirmed voice text no longer offered");
            persist();
            publishState();
        }
    }

    private static int length(String text) {
        return text == null ? -1 : text.length();
    }

    /** Call when the keyboard view finishes: leaving the app or field stops the microphone. */
    public void onInputViewFinished() {
        if (!host.isMainThread() || session == null || targetLost) return;
        InputConnection connection = currentTargetConnection();
        // Remember the text around the cursor now, so the same document can be recognised
        // when it comes back.
        if (connection != null) reconcileContinuity(connection);
        if (recording) stop();
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
        final String raw = text.trim();
        final String previousRaw = sessionRawText;
        sessionRawText = joinWords(sessionRawText, raw);
        if (targetLost) {
            // The field is gone: the words go to Copy as spoken, not formatted against a
            // field that is not there.
            String previousCopy = session.text;
            VoiceInterval.State previousState = session.state;
            session.text = joinWords(session.text, raw);
            // These words never reached a field.
            session.markUndelivered();
            if (!persist()) {
                session.text = previousCopy;
                session.state = previousState;
                sessionRawText = previousRaw;
                message = getString(R.string.voice_status_save_text_failed);
                stateError = true;
                publishState();
                return false;
            }
            nextPieceSequence = pieceSequence + 1;
            publishState();
            return true;
        }
        if (!host.voiceCommitReady() || !deferredPieces.isEmpty()
                || currentTargetConnection() == null && autoDeliveryOpen && !targetLost) {
            // A finger on the keyboard, or the field is away: keep the raw words durable
            // before acknowledging native; format them against the cursor only when they
            // can be written.
            String previousCopy = session.text;
            session.text = joinWords(session.text, raw);
            deferredPieces.add(new DeferredPiece(raw, pauseBeforeSeconds));
            if (!persist()) {
                deferredPieces.remove(deferredPieces.size() - 1);
                session.text = previousCopy;
                sessionRawText = previousRaw;
                message = getString(R.string.voice_status_save_text_failed);
                stateError = true;
                publishState();
                return false;
            }
            if (deferredPieces.size() == 1) deferredCopyStart = previousCopy.length();
            nextPieceSequence = pieceSequence + 1;
            if (host.voiceCommitReady()) deliverStagedText();
            publishState();
            return true;
        }
        InputConnection connection = currentTargetConnection();
        if (connection != null) reconcileContinuity(connection);

        String oldTail = joiner.pendingTail();
        String previousCopy = session.text;
        String previousUndelivered = undeliveredText;
        boolean previousRefused = numberRefused;
        String candidate = numberChecked(joinPiece(raw, pauseBeforeSeconds,
                ownedBefore(""), PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece)));
        session.text += candidate;
        undeliveredText += candidate;
        if (!persist()) {
            joiner.restorePendingTail(oldTail);
            session.text = previousCopy;
            undeliveredText = previousUndelivered;
            sessionRawText = previousRaw;
            numberRefused = previousRefused;
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            publishState();
            return false;
        }
        nextPieceSequence = pieceSequence + 1;
        hasAcceptedPiece = true;
        if (!undeliveredText.isEmpty()) deliverStagedText();
        publishState();
        return true;
    }

    private void finishSession(long sessionId, int outcome, String text, String error) {
        if (sessionId != activeSessionId || terminal) return;
        if (outcome == OUTCOME_SUCCESS && !deferredPieces.isEmpty()) {
            if (host.voiceCommitReady()) deliverStagedText();
            if (!deferredPieces.isEmpty()) {
                boolean waiting = autoDeliveryOpen && !targetLost
                        && (!host.voiceCommitReady() || currentTargetConnection() == null);
                if (waiting && currentTargetConnection() != null) {
                    // A finger is on the keyboard: finish after the swipe reaches the field.
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
                if (!waiting) {
                    // A write or editor preparation failed. The raw words are durable.
                    session.markUndelivered();
                    deferredPieces.clear();
                    deferredCopyStart = -1;
                }
                // Otherwise the field is away: the raw pieces wait for it to come back.
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
            if (connection != null && host.voiceCommitReady()) reconcileContinuity(connection);
            if (deferredPieces.isEmpty()) {
                String tail = joiner.finish();
                if (!tail.isEmpty()) {
                    undeliveredText += tail;
                    session.text += tail;
                }
            }
            if (hasStagedText()) deliverStagedText();
            if (numberRefused) {
                // X2: the field could not take the number; Copy keeps the words as spoken.
                session.text = sessionRawText;
                session.markUndelivered();
            }
            if (sessionRawText.trim().isEmpty() && (text == null || text.trim().isEmpty())) {
                // X6: an information line, not a failure; it clears itself.
                showNotice(getString(R.string.voice_status_nothing_heard));
            }
        } else if (outcome == OUTCOME_REVIEW) {
            joiner.finish();
            dropStagedText();
            if (text != null && !text.trim().isEmpty()) session.text = text;
            session.markUndelivered();
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native review: " + error);
            message = getString(R.string.voice_status_review);
            stateError = true;
        } else if (outcome == OUTCOME_RETRYABLE) {
            // Retry is not a control any more: release the kept audio at once.
            try {
                cancelRecording(sessionId);
            } catch (Throwable cancelError) {
                Log.w(TAG, "Could not release retry audio", cancelError);
            }
            joiner.finish();
            dropStagedText();
            session.markUndelivered();
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native retryable failure: " + error);
            message = getString(R.string.voice_status_failed);
            stateError = true;
        } else if (outcome == OUTCOME_CANCELLED) {
            joiner.finish();
            dropStagedText();
            message = getString(R.string.voice_status_canceled);
        } else {
            joiner.finish();
            dropStagedText();
            session.markUndelivered();
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native failure: " + error);
            message = getString(outcome == OUTCOME_INTERRUPTED
                    ? R.string.voice_status_interrupted : R.string.voice_status_failed);
            stateError = true;
        }
        if (maySaveCurrentSession) {
            // Native sends the full revised candidate for REVIEW.
            String historyText = (outcome == OUTCOME_REVIEW || session.text.isBlank())
                    && text != null && !text.isBlank() ? text : session.text;
            if (!historyText.isBlank() && !host.saveDictation(sessionId, historyText)) {
                message = getString(R.string.voice_status_history_failed);
                stateError = true;
            }
        }
        settleSession();
        publishState();
    }

    private void deliverStagedText() {
        if (session == null || !hasStagedText()) return;
        if (!autoDeliveryOpen || targetLost) {
            // The field refused text or is gone: the words stay in the session for Copy.
            session.markUndelivered();
            dropStagedText();
            persist();
            return;
        }
        InputConnection connection = currentTargetConnection();
        if (connection == null) return; // The field is away; wait for it or for its loss.

        if (!host.voiceCommitReady()) {
            Log.i(TAG, "Voice delivery deferred for touch, gesture or rotation");
            return;
        }
        final boolean leadSpace = !session.destination.readBackKnown
                && (leadSpacePending || host.spacePendingBeforeVoice());
        if (!prepareHostForVoiceCommit()) {
            message = getString(R.string.voice_status_editor_failed);
            stateError = true;
            return;
        }
        if (!deferredPieces.isEmpty()) {
            reconcileContinuity(connection);
            if (!stageDeferredPieces()) return;
            if (terminal && !completionDeferred) {
                String tail = joiner.finish();
                undeliveredText += tail;
                session.text += tail;
            }
        }
        if (undeliveredText.isEmpty()) {
            persist();
            return;
        }
        refreshContext(connection);
        EditorSnapshot before = readSnapshot(connection, session.destination);
        String sent = leadSpace && !Character.isWhitespace(undeliveredText.charAt(0))
                ? " " + undeliveredText : undeliveredText;
        boolean accepted;
        try {
            accepted = connection.commitText(sent, 1);
        } catch (Throwable error) {
            Log.w(TAG, "Editor commit failed", error);
            accepted = false;
        }
        EditorSnapshot after = accepted ? readSnapshot(connection, session.destination) : null;
        finishHostVoiceCommit();
        leadSpacePending = false;
        undeliveredText = "";
        if (!accepted) {
            autoDeliveryOpen = SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(false);
            session.markUndelivered();
            message = getString(R.string.voice_status_delivery_rejected);
        } else if (before == null || after == null || !after.isExactCommitOf(before, sent)) {
            // accepted=true alone does not prove the text arrived.
            Log.i(TAG, "Voice commit not confirmed; read-back "
                    + (session.destination.readBackKnown ? "mismatch" : "unknown")
                    + ", sent length " + sent.length());
            session.markUnconfirmed();
            refreshContext(connection);
            message = getString(R.string.voice_status_delivery_uncertain);
        } else {
            if (session.state == VoiceInterval.State.STAGED) {
                session.state = VoiceInterval.State.CONFIRMED;
            }
            refreshContext(connection, after);
        }
        persist();
    }

    /** Retries staged text once a touch or swipe has ended, or the field has come back. */
    public void resumePendingDelivery() {
        if (!host.isMainThread() || session == null) return;
        if (hasStagedText()) {
            Log.i(TAG, "Resuming pending voice delivery");
            deliverStagedText();
        }
        if (completionDeferred && host.voiceCommitReady() && deferredPieces.isEmpty()) {
            completionDeferred = false;
            finishSession(activeSessionId, deferredOutcome, deferredFinalText, deferredError);
            return;
        }
        settleSession();
        publishState();
    }

    /** Replays saved raw pieces against the cursor after the touch, before any commit. */
    private boolean stageDeferredPieces() {
        String rawCopy = session.text;
        String oldTail = joiner.pendingTail();
        boolean oldAccepted = hasAcceptedPiece;
        boolean oldRefused = numberRefused;
        session.text = rawCopy.substring(0, deferredCopyStart);
        StringBuilder rendered = new StringBuilder();
        for (DeferredPiece piece : deferredPieces) {
            String candidate = numberChecked(joinPiece(piece.text, piece.pauseBeforeSeconds,
                    ownedBefore(rendered.toString()),
                    PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece)));
            rendered.append(candidate);
            session.text += candidate;
            hasAcceptedPiece = true;
        }
        String oldUndelivered = undeliveredText;
        undeliveredText += rendered;
        ArrayList<DeferredPiece> oldPieces = new ArrayList<>(deferredPieces);
        deferredPieces.clear();
        if (!persist()) {
            deferredPieces.addAll(oldPieces);
            undeliveredText = oldUndelivered;
            session.text = rawCopy;
            joiner.restorePendingTail(oldTail);
            hasAcceptedPiece = oldAccepted;
            numberRefused = oldRefused;
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            return false;
        }
        deferredCopyStart = -1;
        return true;
    }

    private String joinPiece(String text, float pauseBeforeSeconds, CharSequence before,
                             int capsMode) {
        return joiner.join(text, pauseBeforeSeconds, before, expectedAfter,
                session.destination.kind, capsMode, sentencePauseSeconds);
    }

    /** The text voice owns before the cursor: the last reading plus what it has staged. */
    private CharSequence ownedBefore(String rendered) {
        if (expectedBefore == null) return null;
        return expectedBefore + undeliveredText + rendered;
    }

    /**
     * X2: a number field gets the piece only when the whole field value afterwards is a
     * number it allows. Otherwise it gets nothing and the words go to Copy.
     */
    private String numberChecked(String candidate) {
        if (session.destination.kind != TextFitter.FieldKind.NUMBER) return candidate;
        String value = (expectedBefore == null ? "" : expectedBefore) + undeliveredText
                + candidate + (expectedAfter == null ? "" : expectedAfter);
        if (!candidate.isEmpty() && session.destination.acceptsNumberValue(value)) return candidate;
        numberRefused = true;
        return "";
    }

    private static String joinWords(String left, String right) {
        if (left.isEmpty() || Character.isWhitespace(left.charAt(left.length() - 1))) {
            return left + right;
        }
        return left + " " + right;
    }

    private boolean hasStagedText() {
        return !undeliveredText.isEmpty() || !deferredPieces.isEmpty();
    }

    private void dropStagedText() {
        undeliveredText = "";
        deferredPieces.clear();
        deferredCopyStart = -1;
    }

    /** The target field is gone for good: stop listening and keep every word for Copy. */
    private void loseTarget() {
        keepHeldTail();
        targetLost = true;
        autoDeliveryOpen = false;
        message = getString(R.string.voice_status_field_changed);
        if (recording) stop();
        if (hasStagedText()) {
            session.markUndelivered();
            dropStagedText();
        }
        if (completionDeferred) {
            completionDeferred = false;
            finishSession(activeSessionId, deferredOutcome, deferredFinalText, deferredError);
            return;
        }
        settleSession();
    }

    /**
     * The joiner holds the last piece's full stop until the next piece decides it. When the
     * field is lost, that mark belongs to the Copy text, right after the formatted words.
     */
    private void keepHeldTail() {
        String tail = joiner.abandonHeldTail();
        if (tail.isEmpty()) return;
        if (deferredPieces.isEmpty() || deferredCopyStart < 0) {
            session.text += tail;
        } else if (deferredCopyStart <= session.text.length()) {
            session.text = session.text.substring(0, deferredCopyStart) + tail.trim()
                    + session.text.substring(deferredCopyStart);
        }
    }

    /** A finished recording whose words still wait for their field becomes a Copy item. */
    private void abandonStagedText() {
        if (session == null || !terminal || !hasStagedText()) return;
        session.markUndelivered();
        dropStagedText();
        settleSession();
    }

    /** Moves a finished recording that needs recovery into the Copy list. */
    private void settleSession() {
        if (session == null || !terminal || completionDeferred) return;
        if (hasStagedText()) {
            persist();
            return;
        }
        if (session == settledSession) {
            // Already decided; a Copy the user's edit or a copy removed stays removed.
            persist();
            return;
        }
        settledSession = session;
        boolean needsRecovery = session.state == VoiceInterval.State.UNCONFIRMED
                || session.state == VoiceInterval.State.UNDELIVERED;
        if (needsRecovery && SessionDraftPolicy.hasLetterOrDigit(session.text)
                && !recoveries.contains(session)) {
            recoveries.add(session);
            while (recoveries.size() > MAX_RECOVERIES) recoveries.remove(0);
        }
        persist();
    }

    /** The newest result offered in the current field, or null. */
    private VoiceInterval offeredRecovery() {
        EditorRecord current = host.currentEditor();
        for (int i = recoveries.size() - 1; i >= 0; i--) {
            VoiceInterval item = recoveries.get(i);
            if (SessionDraftPolicy.copyOffered(item, current, bindingGeneration)) return item;
        }
        return null;
    }

    private InputConnection currentTargetConnection() {
        EditorRecord current = host.currentEditor();
        if (session == null || targetLost || !autoDeliveryOpen
                || session.binding != bindingGeneration || current == null || current.noField) {
            return null;
        }
        return host.currentInputConnection();
    }

    private boolean reconcileContinuity(InputConnection connection) {
        String before = readBefore(connection, session.destination);
        String after = readAfter(connection, session.destination);
        EditorSnapshot snapshot = readSnapshot(connection, session.destination);
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
        refreshContext(connection, readSnapshot(connection, session.destination));
    }

    private void refreshContext(InputConnection connection, EditorSnapshot snapshot) {
        String before = readBefore(connection, session.destination);
        String after = readAfter(connection, session.destination);
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

    private void showNotice(String text) {
        message = text;
        notice = true;
        final long token = ++noticeToken;
        host.postToMainDelayed(() -> {
            if (token != noticeToken || !notice) return;
            notice = false;
            message = getString(R.string.voice_status_ready);
            publishState();
        }, NOTICE_MILLIS);
    }

    private void clearNotice() {
        notice = false;
        noticeToken++;
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
                message = getString(R.string.voice_status_saved_damaged);
                stateError = true;
                return;
            }
            activeSessionId = restored.sessionId;
            if (!SessionDraftPolicy.hasLetterOrDigit(restored.text)) {
                clearDraftFile();
                return;
            }
            // After a restart the field is unknown: the words are offered in ordinary fields.
            VoiceInterval item = new VoiceInterval(restored.sessionId, null, -1);
            item.text = restored.text;
            item.markUndelivered();
            recoveries.add(item);
            message = getString(R.string.voice_status_saved_available);
        } catch (FileNotFoundException ignored) {
            // First run.
        } catch (Throwable error) {
            Log.e(TAG, "Could not restore pending dictation", error);
            message = getString(R.string.voice_status_saved_unreadable);
            stateError = true;
        }
    }

    /**
     * Keeps the one draft file equal to the text that would be lost by a crash now: the
     * current recording's words while any of them wait to be written, else the newest
     * undelivered result. Text from a private field is never written; it lives in memory
     * only, bound to its field.
     */
    private boolean persist() {
        PendingDictationDraft wanted = null;
        if (session != null && !session.privateOrigin() && hasStagedText()) {
            String text = session.text + joiner.pendingTail();
            if (SessionDraftPolicy.hasLetterOrDigit(text)) {
                wanted = new PendingDictationDraft(session.sessionId, nextPieceSequence,
                        PendingDictationDraft.PENDING, text);
            }
        }
        for (int i = recoveries.size() - 1; wanted == null && i >= 0; i--) {
            VoiceInterval item = recoveries.get(i);
            if (item.state == VoiceInterval.State.UNDELIVERED && !item.privateOrigin()) {
                wanted = new PendingDictationDraft(item.sessionId, 0,
                        PendingDictationDraft.INTERRUPTED, item.text);
            }
        }
        return wanted == null ? clearDraftFile() : writeDraft(wanted);
    }

    private boolean writeDraft(PendingDictationDraft draft) {
        FileOutputStream output = null;
        try {
            output = draftFile.startWrite();
            output.write(draft.encode());
            draftFile.finishWrite(output);
            output = null;
            PendingDictationDraft check = PendingDictationDraft.decode(draftFile.readFully());
            return check != null && check.sessionId == draft.sessionId
                    && check.nextSequence == draft.nextSequence
                    && check.state.equals(draft.state) && check.text.equals(draft.text);
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
        return !base.exists() && !new File(base.getPath() + ".bak").exists()
                && !new File(base.getPath() + ".new").exists();
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

    /** Null when the field cannot be read back (a terminal): its answer is not the field. */
    private String readBefore(InputConnection connection, EditorRecord editor) {
        if (connection == null || editor == null || !editor.readBackKnown) return null;
        try {
            CharSequence value = connection.getTextBeforeCursor(CONTEXT_BEFORE_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private String readAfter(InputConnection connection, EditorRecord editor) {
        if (connection == null || editor == null || !editor.readBackKnown) return null;
        try {
            CharSequence value = connection.getTextAfterCursor(CONTEXT_AFTER_CHARS, 0);
            return value == null ? null : value.toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private EditorSnapshot readSnapshot(InputConnection connection, EditorRecord editor) {
        if (connection == null || editor == null || !editor.readBackKnown) return null;
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
        boolean canCopy = terminal && phase == Phase.IDLE && offeredRecovery() != null;
        host.postVoiceState(new VoiceState(phase, message, level, canCopy, stateError, notice));
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
    private native boolean transcribeNowRecording(long sessionId);
    private native boolean cancelRecording(long sessionId);
    private native boolean retryRecording(long sessionId);
}
