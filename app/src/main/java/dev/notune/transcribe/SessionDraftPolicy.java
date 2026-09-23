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

    /** True when the same field no longer has the cursor/text state last owned by voice. */
    static boolean contextChanged(String expectedBefore, String expectedAfter,
                                  int expectedSelectionStart, int expectedSelectionEnd,
                                  String currentBefore, String currentAfter,
                                  int currentSelectionStart, int currentSelectionEnd) {
        if (expectedSelectionStart >= 0 && expectedSelectionEnd >= 0
                && currentSelectionStart >= 0 && currentSelectionEnd >= 0
                && (expectedSelectionStart != currentSelectionStart
                || expectedSelectionEnd != currentSelectionEnd)) {
            return true;
        }
        return expectedBefore != null && currentBefore != null
                && !expectedBefore.equals(currentBefore)
                || expectedAfter != null && currentAfter != null
                && !expectedAfter.equals(currentAfter);
    }
}
