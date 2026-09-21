package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class SessionDraftPolicyTest {
    @Test public void aChangedEditorSavesAndCannotReplayWhenFocusReturns() {
        Object original = new Object();
        SessionDraftPolicy.Delivery changed = SessionDraftPolicy.automatic(
                original, new Object(), true, true);
        assertEquals(SessionDraftPolicy.Delivery.SAVE, changed);

        boolean deliveryStillOpen = changed == SessionDraftPolicy.Delivery.INSERT;
        assertEquals(SessionDraftPolicy.Delivery.SAVE,
                SessionDraftPolicy.automatic(
                        original, original, true, deliveryStillOpen));
        assertEquals(SessionDraftPolicy.Delivery.INSERT,
                SessionDraftPolicy.automatic(original, original, true, true));
    }
}

