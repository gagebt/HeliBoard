package dev.notune.transcribe;

/**
 * One stretch of dictated text with its own destination and delivery state (seam S1).
 * The controller keeps the current session and the latest recovery interval. Each
 * retains the field and binding it was spoken into.
 */
public final class VoiceInterval {
    public enum State {
        /** Text is waiting to be written, or is being written. */
        STAGED,
        /** Every write was read back exactly. */
        CONFIRMED,
        /** The editor accepted the text but it could not be read back exactly. */
        UNCONFIRMED,
        /** Some text never reached the field: refused, field left, or recognition failed. */
        UNDELIVERED
    }

    public final long sessionId;
    /** Null for text restored from the draft file after a restart. */
    public final EditorRecord destination;
    /** The controller's binding generation of the destination; -1 when unknown. */
    public final long binding;
    String text = "";
    State state = State.STAGED;

    VoiceInterval(long sessionId, EditorRecord destination, long binding) {
        this.sessionId = sessionId;
        this.destination = destination;
        this.binding = binding;
    }

    public String text() {
        return text;
    }

    public State state() {
        return state;
    }

    boolean privateOrigin() {
        return destination != null && destination.privateField;
    }

    /** Marks a write that was accepted but not read back; a refusal or loss stays worse. */
    void markUnconfirmed() {
        if (state == State.STAGED || state == State.CONFIRMED) state = State.UNCONFIRMED;
    }

    void markUndelivered() {
        state = State.UNDELIVERED;
    }
}
