package dev.notune.transcribe;

import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.PersistableBundle;
import android.os.SystemClock;
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
 *
 * <p>Editor entry points (seam S1): HeliBoard calls {@link #onEditorStarted} after each
 * {@code onStartInput} and {@link #onInputViewFinished} when the keyboard view finishes.
 * Each recording is one {@link VoiceInterval} bound to an {@link EditorRecord} and a
 * binding generation. Copy offers only the latest recording that needs recovery.
 */
public final class RustInputMethodService extends ContextWrapper implements AutoCloseable {
    private static final String TAG = "NoTuneVoice";
    // Legacy pending-dictation may merge recordings. Preserve it, but never offer it as latest.
    private static final String DRAFT_FILE = "pending-dictation-latest";
    private static final int CONTEXT_BEFORE_CHARS = 2048;
    private static final int CONTEXT_AFTER_CHARS = 512;
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
        public final boolean transcribing;

        VoiceState(Phase phase, String message, float level, boolean canCopy,
                   boolean error, boolean notice, boolean transcribing) {
            this.phase = phase;
            this.message = message;
            this.level = level;
            this.canCopy = canCopy;
            this.error = error;
            this.notice = notice;
            this.transcribing = transcribing;
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
    /** Capture and delivery have separate owners while an earlier recording finishes. */
    private long captureId;
    private long busySessionId;
    private Recording delivering;
    private final ArrayList<Recording> waiting = new ArrayList<>();
    private int pendingDelete;
    private boolean crossRecordingTail;
    private boolean alwaysFullStop;
    private float firstPiecePause = -1;

    private static final class Recording {
        final VoiceInterval interval;
        final boolean saveHistory;
        final long startedAt;
        final ArrayList<DeferredPiece> pieces = new ArrayList<>();
        long stoppedAt;
        long nextSequence;
        boolean complete;
        int outcome;
        String finalText;
        String error;
        EditorSnapshot snapshot;

        Recording(VoiceInterval interval, boolean saveHistory) {
            this.interval = interval;
            this.saveHistory = saveHistory;
            startedAt = SystemClock.elapsedRealtime();
        }
    }
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
    /** The finished session already considered for Copy. */
    private VoiceInterval settledSession;
    private int deferredCopyStart = -1;
    private boolean completionDeferred;
    private int deferredOutcome;
    private String deferredFinalText;
    private String deferredError;
    private boolean maySaveCurrentSession;
    /** Only the latest recording may be offered for Copy. */
    private VoiceInterval recovery;

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
        if (!initialized || captureId != 0 || !host.isMainThread()) return false;
        EditorInfo info = host.currentEditorInfo();
        EditorRecord editor = host.currentEditor();
        if (host.currentInputConnection() == null || info == null || editor == null) {
            message = getString(R.string.voice_status_no_field);
            stateError = true;
            publishState();
            return false;
        }
        abandonStagedText();
        if (!prepareHostForVoiceCommit()) {
            message = getString(R.string.voice_status_finish_gesture);
            stateError = true;
            publishState();
            return false;
        }
        final long id = Math.max(activeSessionId + 1, Math.max(1, System.nanoTime()));
        boolean started;
        try {
            started = startRecording(id);
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
        Recording next = new Recording(new VoiceInterval(id, editor, bindingGeneration),
                host.maySaveDictation(editor));
        Log.i(TAG, "Voice capture started: " + id);
        next.snapshot = readSnapshot(host.currentInputConnection(), editor);
        captureId = id;
        recording = true;
        if (terminal && !completionDeferred && !hasStagedText()) activate(next);
        else waiting.add(next);
        stateError = false;
        clearNotice();
        message = getString(R.string.voice_status_listening);
        level = 0;
        publishState();
        return true;
    }

    /** Begin delivery only after the previous recording has completed its writes. */
    private void activate(Recording next) {
        InputConnection connection = host.currentInputConnection();
        EditorRecord editor = next.interval.destination;
        boolean sameSpot = session != null && session.binding == next.interval.binding
                && session.destination.sameField(editor) && !targetLost;
        if (sameSpot && connection != null) reconcileContinuity(connection);
        else joiner.abandonHeldTail();
        long previousStop = delivering == null ? 0 : delivering.stoppedAt;
        crossRecordingTail = sameSpot && joiner.hasHeldTail() && previousStop > 0;
        float gap = previousStop > 0 ? Math.max(0, next.startedAt - previousStop) / 1000f : 0;
        delivering = next;
        activeSessionId = next.interval.sessionId;
        session = next.interval;
        nextPieceSequence = 0;
        targetLost = session.binding != bindingGeneration;
        maySaveCurrentSession = next.saveHistory;
        EditorInfo info = host.currentEditorInfo();
        try {
            targetCapsMode = connection == null || info == null ? 0
                    : connection.getCursorCapsMode(info.inputType);
        } catch (Throwable ignored) {
            targetCapsMode = 0;
        }
        expectedBefore = readBefore(connection, editor);
        expectedAfter = readAfter(connection, editor);
        EditorSnapshot snapshot = readSnapshot(connection, editor);
        expectedSelectionStart = selectionStart(snapshot);
        expectedSelectionEnd = selectionEnd(snapshot);
        sentencePauseSeconds = readSentencePauseSeconds();
        alwaysFullStop = "always".equals(helium314.keyboard.latin.utils.KtxKt.prefs(this).getString(
                        helium314.keyboard.latin.settings.Settings.PREF_VOICE_FULL_STOP,
                        helium314.keyboard.latin.settings.Defaults.PREF_VOICE_FULL_STOP));
        hasAcceptedPiece = false;
        leadSpacePending = !editor.readBackKnown && host.spacePendingBeforeVoice();
        numberRefused = false;
        terminal = false;
        autoDeliveryOpen = !targetLost;
        undeliveredText = "";
        pendingDelete = 0;
        sessionRawText = "";
        session.text = "";
        deferredPieces.clear();
        deferredCopyStart = -1;
        completionDeferred = false;
        // Capture time, never model latency, decides the boundary between recordings.
        firstPiecePause = crossRecordingTail ? gap : -1;
        if (!next.pieces.isEmpty()) {
            deferredPieces.addAll(next.pieces);
            next.pieces.clear();
            for (DeferredPiece piece : deferredPieces) {
                sessionRawText = joinWords(sessionRawText, piece.text);
            }
            session.text = sessionRawText;
            deferredCopyStart = 0;
            nextPieceSequence = next.nextSequence;
            deliverStagedText();
        }
        if (next.complete) finishSession(activeSessionId, next.outcome, next.finalText, next.error);
    }

    private Recording recording(long id) {
        if (delivering != null && delivering.interval.sessionId == id) return delivering;
        for (Recording item : waiting) if (item.interval.sessionId == id) return item;
        return null;
    }

    public boolean stop() {
        if (captureId == 0 || !host.isMainThread()) return false;
        final long id = captureId;
        Log.i(TAG, "Voice stop requested: " + id);
        Recording item = recording(id);
        if (item != null) item.stoppedAt = SystemClock.elapsedRealtime();
        captureId = 0;
        recording = false;
        message = getString(R.string.voice_status_finishing);
        boolean accepted;
        try {
            accepted = stopRecording(id);
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
        if (captureId == 0 || busySessionId != 0 || !host.isMainThread()) return -1;
        try {
            return transcribeNowRecording(captureId);
        } catch (Throwable error) {
            Log.e(TAG, "Could not transcribe current audio", error);
            message = getString(R.string.voice_status_transcribe_failed);
            stateError = true;
            publishState();
            return -1;
        }
    }

    public boolean cancel() {
        if (captureId == 0 || !host.isMainThread()) return false;
        long id = captureId;
        captureId = 0;
        recording = false;
        message = getString(R.string.voice_status_canceling);
        boolean accepted;
        try {
            accepted = cancelRecording(id);
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
            if (item.privateOrigin()) {
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
        recovery = null;
        if (!persist()) {
            // The saved copy is still on disk: keep offering it rather than claim it is gone.
            recovery = item;
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
        onEditorPrivacyChanged();
        EditorRecord current = host.currentEditor();
        if (current != null && current.noField) {
            // Home shows the launcher, which has no field: wait for the next real field.
            publishState();
            return;
        }
        Recording viewOwner = waiting.isEmpty() ? null : waiting.get(waiting.size() - 1);
        if (viewOwner != null && current != null
                && viewOwner.interval.destination.sameField(current)
                && (rotating || viewOwner.snapshot != null && viewOwner.snapshot.sameAs(
                        readSnapshot(host.currentInputConnection(), current)))) {
            resumePendingDelivery();
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
        if (captureId != 0 && captureId != activeSessionId) stop();
        // Unconfirmed text is offered only until the user leaves its field.
        if (recovery != null && recovery.state == VoiceInterval.State.UNCONFIRMED
                && !recovery.closeFinalized) {
            recovery = null;
        }
        if (session != null && !targetLost && (!terminal || hasStagedText())) {
            Log.i(TAG, "Voice target left; words kept for Copy");
            loseTarget();
        }
        publishState();
    }

    /** A live privacy change protects active, queued and retained words in this binding. */
    public void onEditorPrivacyChanged() {
        if (!host.isMainThread()) return;
        EditorRecord current = host.currentEditor();
        if (current == null || !current.privateField) return;
        markPrivateInCurrentField(session, current);
        markPrivateInCurrentField(recovery, current);
        for (Recording item : waiting) markPrivateInCurrentField(item.interval, current);
        persist();
    }

    private void markPrivateInCurrentField(VoiceInterval item, EditorRecord current) {
        if (item != null && item.binding == bindingGeneration && item.destination != null
                && item.destination.sameField(current)) item.markPrivate();
    }

    /**
     * Call after the user's own edit in the current field (a typed key or a swipe word).
     * Words whose delivery is uncertain may already be in the field; once the user edits,
     * a Copy of them would duplicate or mislead, so it ends. Undelivered words stay.
     */
    public void onUserEdit() {
        if (!host.isMainThread()) return;
        if (session != null && session.binding == bindingGeneration) {
            joiner.abandonHeldTail();
            crossRecordingTail = false;
            firstPiecePause = -1;
        }
        rememberWaitingContext();
        if (recovery != null && (recovery.state == VoiceInterval.State.UNCONFIRMED
                || recovery.closeFinalized) && recovery.binding == bindingGeneration) {
            recovery = null;
            Log.i(TAG, "User edited the field; unconfirmed voice text no longer offered");
            persist();
            publishState();
        }
    }

    private static int length(String text) {
        return text == null ? -1 : text.length();
    }

    /** Remember the field when the view closes, and stop if the close policy requires it. */
    public void onInputViewFinished(boolean stopOnClose) {
        if (!host.isMainThread()) return;
        rememberWaitingContext();
        if (session != null && !targetLost) {
            InputConnection connection = currentTargetConnection();
            if (connection != null) reconcileContinuity(connection);
        }
        if (recording && stopOnClose) {
            Recording item = recording(captureId);
            if (item != null) item.interval.closeFinalized = true;
            else if (session != null) session.closeFinalized = true;
            stop();
        }
    }

    @Override public void close() {
        if (!initialized) return;
        try {
            cleanupNative();
        } catch (Throwable error) {
            Log.w(TAG, "Native cleanup failed", error);
        }
        initialized = false;
        captureId = 0;
        busySessionId = 0;
        waiting.clear();
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
            if (sessionId != (captureId != 0 ? captureId : activeSessionId)) return;
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
            if (sessionId != captureId) return;
            level = newLevel;
        });
    }

    public void onAutoStop(long sessionId) {
        onMain(() -> {
            if (sessionId != captureId || !recording) return;
            stop();
        });
    }

    public void onTranscriptionBusy(long sessionId, boolean busy) {
        onMain(() -> {
            Log.i(TAG, "Voice inference: " + sessionId + ", busy " + busy);
            if (busy) busySessionId = sessionId;
            else if (busySessionId == sessionId) busySessionId = 0;
            publishState();
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
        if (text == null || text.trim().isEmpty()) return false;
        if (sessionId != activeSessionId) {
            Recording item = recording(sessionId);
            if (item == null || item.complete) return false;
            if (pieceSequence < item.nextSequence) return true;
            String previous = item.interval.text;
            item.pieces.add(new DeferredPiece(text.trim(), pauseBeforeSeconds));
            item.interval.text = joinWords(previous, text.trim());
            if (!persist()) {
                item.pieces.remove(item.pieces.size() - 1);
                item.interval.text = previous;
                return false;
            }
            item.nextSequence = pieceSequence + 1;
            recovery = null;
            return true;
        }
        if (terminal) return false;
        if (pieceSequence < nextPieceSequence) return true;
        final String raw = text.trim();
        final String previousRaw = sessionRawText;
        sessionRawText = joinWords(sessionRawText, raw);
        if (targetLost || !autoDeliveryOpen) {
            keepHeldTail();
            // The field is gone: the words go to Copy as spoken, not formatted against a
            // field that is not there.
            String previousCopy = session.text;
            VoiceInterval.State previousState = session.state;
            String copyPiece = session.destination.kind == TextFitter.FieldKind.PROSE
                    ? TextFitter.fit(raw, null, null, session.destination.kind, 0, alwaysFullStop).text
                    : raw;
            session.text = joinWords(session.text, copyPiece);
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
            recovery = null;
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
            recovery = null;
            nextPieceSequence = pieceSequence + 1;
            if (host.voiceCommitReady()) deliverStagedText();
            publishState();
            return true;
        }
        InputConnection connection = currentTargetConnection();
        if (connection != null) reconcileContinuity(connection);

        PieceJoiner.PendingTail oldTail = joiner.pendingTail();
        String previousCopy = session.text;
        String previousUndelivered = undeliveredText;
        boolean previousRefused = numberRefused;
        int previousDelete = pendingDelete;
        boolean previousCross = crossRecordingTail;
        float previousPause = firstPiecePause;
        appendJoin(joinPiece(raw, pauseBeforeSeconds,
                PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece)));
        if (!persist()) {
            joiner.restorePendingTail(oldTail);
            session.text = previousCopy;
            undeliveredText = previousUndelivered;
            sessionRawText = previousRaw;
            numberRefused = previousRefused;
            pendingDelete = previousDelete;
            crossRecordingTail = previousCross;
            firstPiecePause = previousPause;
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            publishState();
            return false;
        }
        recovery = null;
        nextPieceSequence = pieceSequence + 1;
        hasAcceptedPiece = true;
        if (!undeliveredText.isEmpty()) deliverStagedText();
        else persist();
        publishState();
        return true;
    }

    private void finishSession(long sessionId, int outcome, String text, String error) {
        Log.i(TAG, "Voice completed: " + sessionId + ", outcome " + outcome);
        if (sessionId == captureId) {
            captureId = 0;
            recording = false;
        }
        if (sessionId != activeSessionId) {
            Recording item = recording(sessionId);
            if (item == null) return;
            item.complete = true;
            item.outcome = outcome;
            item.finalText = text;
            item.error = error;
            publishState();
            return;
        }
        if (terminal) return;
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
        phase = Phase.IDLE;
        level = 0;

        if (outcome == OUTCOME_SUCCESS) {
            InputConnection connection = currentTargetConnection();
            if (connection != null && host.voiceCommitReady()) reconcileContinuity(connection);
            if (deferredPieces.isEmpty()) {
                appendJoin(joiner.finish(alwaysFullStop, afterStartsSentence()));
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
            joiner.abandonHeldTail();
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
            joiner.abandonHeldTail();
            dropStagedText();
            session.markUndelivered();
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native retryable failure: " + error);
            message = getString(R.string.voice_status_failed);
            stateError = true;
        } else if (outcome == OUTCOME_CANCELLED) {
            joiner.abandonHeldTail();
            dropStagedText();
            message = getString(R.string.voice_status_canceled);
        } else {
            joiner.abandonHeldTail();
            dropStagedText();
            session.markUndelivered();
            if (error != null && !error.isEmpty()) Log.w(TAG, "Native failure: " + error);
            message = getString(outcome == OUTCOME_INTERRUPTED
                    ? R.string.voice_status_interrupted : R.string.voice_status_failed);
            stateError = true;
        }
        if (maySaveCurrentSession && !session.privateOrigin()) {
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
                appendJoin(joiner.finish(alwaysFullStop, afterStartsSentence()));
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
        boolean accepted = false;
        int deleted = 0;
        try {
            connection.beginBatchEdit();
            if (pendingDelete != 0) {
                CharSequence live = connection.getTextBeforeCursor(2, 0);
                boolean ownsSpace = live != null && live.length() == 2
                        && !Character.isWhitespace(live.charAt(0)) && live.charAt(1) == ' '
                        && (before == null || before.selectionStart == before.selectionEnd);
                if (ownsSpace) {
                    if (connection.deleteSurroundingText(1, 0)) deleted = 1;
                    else throw new IllegalStateException("Voice separator replacement refused");
                } else if (live == null || live.length() == 0
                        || Character.isWhitespace(live.charAt(live.length() - 1))) {
                    // Context cannot prove our space: omit its owed mark, never delete blindly.
                    sent = sent.replaceFirst("^[.!?] ?", "");
                }
            }
            accepted = connection.commitText(sent, 1);
        } catch (Throwable error) {
            Log.w(TAG, "Editor commit failed", error);
        } finally {
            try { connection.endBatchEdit(); } catch (Throwable ignored) { }
        }
        pendingDelete = 0;
        EditorSnapshot after = accepted ? readSnapshot(connection, session.destination) : null;
        finishHostVoiceCommit();
        leadSpacePending = false;
        undeliveredText = "";
        if (!accepted) {
            autoDeliveryOpen = SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(false);
            session.markUndelivered();
            message = getString(R.string.voice_status_delivery_rejected);
        } else if (before == null || after == null || !after.isExactCommitOf(before, deleted, sent)) {
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
        PieceJoiner.PendingTail oldTail = joiner.pendingTail();
        boolean oldAccepted = hasAcceptedPiece;
        boolean oldRefused = numberRefused;
        boolean oldCross = crossRecordingTail;
        float oldPause = firstPiecePause;
        int oldDelete = pendingDelete;
        String oldUndelivered = undeliveredText;
        session.text = rawCopy.substring(0, deferredCopyStart);
        for (DeferredPiece piece : deferredPieces) {
            appendJoin(joinPiece(piece.text, piece.pauseBeforeSeconds,
                    PieceJoiner.capsModeForPiece(targetCapsMode, hasAcceptedPiece)));
            hasAcceptedPiece = true;
        }
        ArrayList<DeferredPiece> oldPieces = new ArrayList<>(deferredPieces);
        deferredPieces.clear();
        if (!persist()) {
            deferredPieces.addAll(oldPieces);
            undeliveredText = oldUndelivered;
            session.text = rawCopy;
            joiner.restorePendingTail(oldTail);
            hasAcceptedPiece = oldAccepted;
            numberRefused = oldRefused;
            crossRecordingTail = oldCross;
            firstPiecePause = oldPause;
            pendingDelete = oldDelete;
            message = getString(R.string.voice_status_save_text_failed);
            stateError = true;
            return false;
        }
        deferredCopyStart = -1;
        return true;
    }

    private PieceJoiner.Join joinPiece(String text, float pauseBeforeSeconds, int capsMode) {
        if (firstPiecePause >= 0) {
            pauseBeforeSeconds = firstPiecePause;
            firstPiecePause = -1;
        }
        PieceJoiner.Join joined = joiner.join(text, pauseBeforeSeconds, ownedBefore(), expectedAfter,
                session.destination.kind, capsMode, sentencePauseSeconds, alwaysFullStop);
        if (session.destination.kind == TextFitter.FieldKind.NUMBER && joined.text.isEmpty()) {
            numberRefused = true;
        }
        return joined;
    }

    /** Apply the same cursor edit to staged text and this recording's Copy text. */
    private void appendJoin(PieceJoiner.Join join) {
        if (join.text.isEmpty() && join.deleteBefore == 0) return;
        String text = numberChecked(join.text);
        if (join.deleteBefore != 0) {
            if (undeliveredText.endsWith(" ")) {
                undeliveredText = undeliveredText.substring(0, undeliveredText.length() - 1);
            } else {
                pendingDelete = join.deleteBefore;
            }
        }
        if (!crossRecordingTail) {
            if (join.deleteBefore != 0 && session.text.endsWith(" ")) {
                session.text = session.text.substring(0, session.text.length() - 1);
            }
            session.text += text;
        } else {
            // A's boundary belongs in the field, never in B's latest-recording Copy.
            String copy = text.startsWith(". ") ? text.substring(2)
                    : text.startsWith(".") ? text.substring(1) : text;
            session.text += copy;
        }
        crossRecordingTail = false;
        undeliveredText += text;
    }

    private CharSequence ownedBefore() {
        if (expectedBefore == null) return null;
        int keep = Math.max(0, expectedBefore.length() - pendingDelete);
        return expectedBefore.substring(0, keep) + undeliveredText;
    }

    private boolean afterStartsSentence() {
        if (expectedAfter == null || expectedAfter.isBlank()) return false;
        int first = expectedAfter.stripLeading().codePointAt(0);
        return Character.isUpperCase(first) || Character.isDigit(first)
                || first == '(' || first == '[' || first == '"';
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
        pendingDelete = 0;
        deferredPieces.clear();
        deferredCopyStart = -1;
    }

    /** The target field is gone for good: stop listening and keep every word for Copy. */
    private void loseTarget() {
        keepHeldTail();
        targetLost = true;
        autoDeliveryOpen = false;
        message = getString(R.string.voice_status_field_changed);
        if (recording && captureId == activeSessionId) stop();
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
        PieceJoiner.PendingTail tail = joiner.abandonHeldTail();
        if (!alwaysFullStop || tail.mark.isEmpty()) return;
        int at = deferredCopyStart >= 0 ? deferredCopyStart : session.text.length();
        if (tail.ownsSpace && at > 0 && session.text.charAt(at - 1) == ' ') at--;
        session.text = session.text.substring(0, at) + tail.mark + session.text.substring(at);
    }

    /** A finished recording whose words still wait for their field becomes a Copy item. */
    private void abandonStagedText() {
        if (session == null || !terminal || !hasStagedText()) return;
        session.markUndelivered();
        dropStagedText();
        settleSession();
    }

    /** Offers only the finished recording, if its delivery needs recovery. */
    private void settleSession() {
        if (session == null || !terminal || completionDeferred) return;
        if (hasStagedText()) {
            if (waiting.isEmpty()) {
                persist();
                return;
            }
            // A deliberate newer recording replaces the old field's pending delivery.
            session.markUndelivered();
            dropStagedText();
        }
        if (session == settledSession) {
            // Already decided; a Copy the user's edit or a copy removed stays removed.
            persist();
            promoteWaiting();
            return;
        }
        settledSession = session;
        boolean needsRecovery = session.state == VoiceInterval.State.UNCONFIRMED
                || session.state == VoiceInterval.State.UNDELIVERED || session.closeFinalized;
        if (SessionDraftPolicy.hasLetterOrDigit(session.text)) {
            recovery = needsRecovery ? session : null;
        }
        persist();
        promoteWaiting();
    }

    private void promoteWaiting() {
        if (!waiting.isEmpty()) activate(waiting.remove(0));
    }

    /** The newest result offered in the current field, or null. */
    private VoiceInterval offeredRecovery() {
        return SessionDraftPolicy.copyOffered(recovery, host.currentEditor(), bindingGeneration)
                ? recovery : null;
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
        if (before == null && after == null && snapshot == null) {
            // Nothing readable (the app's connection is already inactive when the keyboard
            // view finishes): keep the last known context so the same document is recognised.
            return false;
        }
        boolean changed = SessionDraftPolicy.contextChanged(
                expectedBefore, expectedAfter, expectedSelectionStart, expectedSelectionEnd,
                before, after, selectionStart(snapshot), selectionEnd(snapshot));
        boolean abandonedTail = false;
        if (changed) {
            abandonedTail = !joiner.abandonHeldTail().mark.isEmpty();
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
        rememberWaitingContext();
    }

    private void rememberWaitingContext() {
        EditorRecord current = host.currentEditor();
        if (current == null) return;
        for (Recording item : waiting) {
            if (item.interval.binding == bindingGeneration
                    && item.interval.destination.sameField(current)) {
                EditorSnapshot snapshot = readSnapshot(host.currentInputConnection(), current);
                if (snapshot != null) item.snapshot = snapshot;
            }
        }
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
            recovery = item;
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
     * current recording's words while staged or known undelivered, else the newest
     * undelivered result. Text from a private field is never written; it lives in memory
     * only, bound to its field.
     */
    private boolean persist() {
        PendingDictationDraft wanted = null;
        for (int i = waiting.size() - 1; i >= 0; i--) {
            Recording item = waiting.get(i);
            if (item.interval.privateOrigin() || !SessionDraftPolicy.hasLetterOrDigit(item.interval.text)) continue;
            wanted = new PendingDictationDraft(item.interval.sessionId, item.nextSequence,
                    PendingDictationDraft.PENDING, item.interval.text);
            break;
        }
        if (wanted == null && session != null && !session.privateOrigin()
                && (hasStagedText() || session != settledSession
                && session.state == VoiceInterval.State.UNDELIVERED)) {
            String text = copyTextWithPendingMark();
            if (SessionDraftPolicy.hasLetterOrDigit(text)) {
                wanted = new PendingDictationDraft(session.sessionId, nextPieceSequence,
                        PendingDictationDraft.PENDING, text);
            }
        }
        if (wanted == null && recovery != null
                && (recovery.state == VoiceInterval.State.UNDELIVERED || recovery.closeFinalized)
                && !recovery.privateOrigin()) {
            wanted = new PendingDictationDraft(recovery.sessionId, 0,
                    PendingDictationDraft.INTERRUPTED, recovery.text);
        }
        return wanted == null ? clearDraftFile() : writeDraft(wanted);
    }

    private String copyTextWithPendingMark() {
        PieceJoiner.PendingTail tail = joiner.pendingTail();
        if (!alwaysFullStop || tail.mark.isEmpty()) return session.text;
        int at = deferredCopyStart >= 0 ? deferredCopyStart : session.text.length();
        if (tail.ownsSpace && at > 0 && session.text.charAt(at - 1) == ' ') at--;
        return session.text.substring(0, at) + tail.mark + session.text.substring(at);
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
        phase = recording ? Phase.RECORDING
                : !terminal || !waiting.isEmpty() ? Phase.FINISHING : Phase.IDLE;
        boolean canCopy = terminal && phase == Phase.IDLE && offeredRecovery() != null;
        host.postVoiceState(new VoiceState(phase, message, level, canCopy, stateError, notice,
                busySessionId != 0));
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

        boolean sameAs(EditorSnapshot other) {
            return other != null && startOffset == other.startOffset
                    && selectionStart == other.selectionStart && selectionEnd == other.selectionEnd
                    && text.equals(other.text);
        }

        boolean isExactCommitOf(EditorSnapshot before, int deleted, String inserted) {
            if (before == null || startOffset != before.startOffset
                    || !valid() || !before.valid()) return false;
            int start = Math.min(before.selectionStart, before.selectionEnd);
            int end = Math.max(before.selectionStart, before.selectionEnd);
            if (deleted < 0 || start < deleted) return false;
            String expected = before.text.substring(0, start - deleted) + inserted
                    + before.text.substring(end);
            int cursor = start - deleted + inserted.length();
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
