package dev.notune.transcribe;

import java.util.Objects;

/** Pure decisions at voice-session boundaries; a saved draft never becomes an auto-replay queue. */
final class SessionDraftPolicy {
    enum Delivery { INSERT, SAVE }

    static final class StartResolution {
        final long sessionId;
        final boolean retryAvailable;
        final boolean replaced;

        StartResolution(long sessionId, boolean retryAvailable, boolean replaced) {
            this.sessionId = sessionId;
            this.retryAvailable = retryAvailable;
            this.replaced = replaced;
        }
    }

    private SessionDraftPolicy() { }

    static Delivery automatic(Object targetEditor, Object currentEditor,
                              boolean inputActive, boolean deliveryOpen) {
        return deliveryOpen && inputActive && targetEditor != null
                && Objects.equals(targetEditor, currentEditor)
                ? Delivery.INSERT : Delivery.SAVE;
    }

    static boolean deliveryOpenAfterUnconfirmedCommit(boolean accepted) {
        return accepted;
    }

    static StartResolution afterStartAttempt(long currentSessionId, boolean retryAvailable,
                                             long candidateSessionId, boolean started) {
        return started
                ? new StartResolution(candidateSessionId, false, true)
                : new StartResolution(currentSessionId, retryAvailable, false);
    }

    static boolean canDiscard(boolean hasDraft, boolean draftReadError,
                              boolean retryAvailable) {
        return hasDraft || draftReadError || retryAvailable;
    }
}
