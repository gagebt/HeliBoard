package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

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

    @Test public void acceptedUnconfirmablePieceKeepsLaterPiecesInTheSameEditor() {
        Object editor = new Object();
        boolean deliveryOpen = SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(true);

        assertEquals(SessionDraftPolicy.Delivery.INSERT,
                SessionDraftPolicy.automatic(editor, editor, true, deliveryOpen));
        assertEquals(SessionDraftPolicy.Delivery.SAVE,
                SessionDraftPolicy.automatic(editor, new Object(), true, deliveryOpen));
    }

    @Test public void multiplePiecesAndFinalTailStayOneRecoveryRecord() {
        String firstPiece = "First";
        String laterPiece = " second";
        String finalTail = ".";
        String completeSession = firstPiece + laterPiece + finalTail;

        assertEquals("First second.",
                RecoveryText.settled("", completeSession, true));
    }

    @Test public void rejectedPieceClosesAutomaticDelivery() {
        Object editor = new Object();
        boolean deliveryOpen = SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(false);
        assertEquals(SessionDraftPolicy.Delivery.SAVE,
                SessionDraftPolicy.automatic(editor, editor, true, deliveryOpen));
    }

    @Test public void rejectedNewRecordingPreservesTheRetryableSession() {
        SessionDraftPolicy.StartResolution resolution = SessionDraftPolicy.afterStartAttempt(
                41, true, 42, false);
        assertEquals(41, resolution.sessionId);
        assertTrue(resolution.retryAvailable);
        assertFalse(resolution.replaced);
    }

    @Test public void acceptedNewRecordingReplacesTheOldSession() {
        SessionDraftPolicy.StartResolution resolution = SessionDraftPolicy.afterStartAttempt(
                41, true, 42, true);
        assertEquals(42, resolution.sessionId);
        assertFalse(resolution.retryAvailable);
        assertTrue(resolution.replaced);
    }

    @Test public void retryOnlyFailureOffersDiscard() {
        assertTrue(SessionDraftPolicy.canDiscard(false, false, true));
        assertFalse(SessionDraftPolicy.canDiscard(false, false, false));
    }

    @Test public void unchangedCursorAndContextContinueTheVoiceSegment() {
        assertFalse(SessionDraftPolicy.contextChanged(
                "before", "after", 6, 6, "before", "after", 6, 6));
    }

    @Test public void typedTextStartsANewVoiceSegment() {
        assertTrue(SessionDraftPolicy.contextChanged(
                "before", "after", 6, 6, "before typed", "after", 12, 12));
    }

    @Test public void selectionCoordinatesDetectMovesInRepeatedText() {
        assertTrue(SessionDraftPolicy.contextChanged(
                "same", "same", 4, 4, "same", "same", 20, 20));
    }
}
