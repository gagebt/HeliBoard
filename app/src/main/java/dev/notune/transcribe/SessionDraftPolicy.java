package dev.notune.transcribe;

import java.util.Objects;

/** Pure decision at the editor boundary; a saved draft never becomes an auto-replay queue. */
final class SessionDraftPolicy {
    enum Delivery { INSERT, SAVE }

    private SessionDraftPolicy() { }

    static Delivery automatic(Object targetEditor, Object currentEditor,
                              boolean inputActive, boolean deliveryOpen) {
        return deliveryOpen && inputActive && targetEditor != null
                && Objects.equals(targetEditor, currentEditor)
                ? Delivery.INSERT : Delivery.SAVE;
    }
}

