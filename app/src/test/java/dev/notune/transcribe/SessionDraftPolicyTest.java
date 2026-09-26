package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

public class SessionDraftPolicyTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int PASSWORD = InputType.TYPE_CLASS_TEXT
            | InputType.TYPE_TEXT_VARIATION_PASSWORD;

    private static EditorRecord field(String pkg, int id, int type) {
        return new EditorRecord(pkg, id, type, 0, false);
    }

    private static VoiceInterval result(EditorRecord origin, long binding,
                                        VoiceInterval.State state, String text) {
        VoiceInterval item = new VoiceInterval(7, origin, binding);
        item.text = text;
        item.state = state;
        return item;
    }

    @Test public void acceptedUnconfirmablePieceKeepsDeliveryOpenAndRejectedClosesIt() {
        assertTrue(SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(true));
        assertFalse(SessionDraftPolicy.deliveryOpenAfterUnconfirmedCommit(false));
    }

    @Test public void rotationContinuesOnlyTheSameField() {
        EditorRecord markor = field("net.gsantner.markor", 42, TEXT);
        assertTrue(SessionDraftPolicy.continuesBinding(markor, field("net.gsantner.markor", 42, TEXT),
                true, null, null, -1, -1, null, null, -1, -1));
        assertFalse(SessionDraftPolicy.continuesBinding(markor, field("other.app", 42, TEXT),
                true, null, null, -1, -1, null, null, -1, -1));
    }

    @Test public void returnContinuesOnlyWhenBothSidesAndCursorMatch() {
        EditorRecord doc = field("net.gsantner.markor", 42, TEXT);
        assertTrue(SessionDraftPolicy.continuesBinding(doc, doc, false,
                "Shopping list: milk", "\nbread", 19, 19, "Shopping list: milk", "\nbread", 19, 19));
        // Same widget id, same text before the cursor, another document after it.
        assertFalse(SessionDraftPolicy.continuesBinding(doc, doc, false,
                "Shopping list: milk", "\nbread", 19, 19, "Shopping list: milk", "\neggs", 19, 19));
        // Same text around the cursor at another place in the document.
        assertFalse(SessionDraftPolicy.continuesBinding(doc, doc, false,
                "same", "same", 4, 4, "same", "same", 20, 20));
    }

    @Test public void unreadableOrUnknownFieldsNeverContinueWithoutRotation() {
        EditorRecord termux = field("com.termux", 2131231121, InputType.TYPE_NULL);
        assertFalse(SessionDraftPolicy.continuesBinding(termux, termux, false,
                null, null, -1, -1, null, null, -1, -1));
        EditorRecord noId = field("net.gsantner.markor", 0, TEXT);
        assertFalse(SessionDraftPolicy.continuesBinding(noId, noId, false,
                "a", "b", 1, 1, "a", "b", 1, 1));
        EditorRecord minusOne = field("net.gsantner.markor", -1, TEXT);
        assertFalse(SessionDraftPolicy.continuesBinding(minusOne, minusOne, true,
                null, null, -1, -1, null, null, -1, -1));
    }

    @Test public void unconfirmedTextIsOfferedOnlyInItsOwnBinding() {
        EditorRecord doc = field("net.gsantner.markor", 42, TEXT);
        VoiceInterval item = result(doc, 5, VoiceInterval.State.UNCONFIRMED, "Hello there.");
        assertTrue(SessionDraftPolicy.copyOffered(item, doc, 5));
        assertFalse(SessionDraftPolicy.copyOffered(item, doc, 6));
        assertFalse(SessionDraftPolicy.copyOffered(item, field("other.app", 1, TEXT), 6));
    }

    @Test public void undeliveredTextIsOfferedInOtherOrdinaryFieldsButNotPrivateOnes() {
        EditorRecord doc = field("net.gsantner.markor", 42, TEXT);
        VoiceInterval item = result(doc, 5, VoiceInterval.State.UNDELIVERED, "Hello there.");
        assertTrue(SessionDraftPolicy.copyOffered(item, field("other.app", 1, TEXT), 9));
        assertFalse(SessionDraftPolicy.copyOffered(item, field("other.app", 1, PASSWORD), 9));
        assertFalse(SessionDraftPolicy.copyOffered(item, new EditorRecord("other.app", 1, TEXT,
                EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false), 9));
        assertFalse(SessionDraftPolicy.copyOffered(item, new EditorRecord("other.app", 1, TEXT,
                0, true), 9));
    }

    @Test public void privateFieldTextIsOfferedOnlyInItsOwnField() {
        EditorRecord secret = field("bank.app", 3, PASSWORD);
        VoiceInterval item = result(secret, 5, VoiceInterval.State.UNDELIVERED, "sentinel 42");
        assertTrue(SessionDraftPolicy.copyOffered(item, secret, 5));
        assertFalse(SessionDraftPolicy.copyOffered(item, field("other.app", 1, TEXT), 6));
    }

    @Test public void confirmedOrPunctuationOnlyTextIsNeverOffered() {
        EditorRecord doc = field("net.gsantner.markor", 42, TEXT);
        assertFalse(SessionDraftPolicy.copyOffered(
                result(doc, 5, VoiceInterval.State.CONFIRMED, "Hello."), doc, 5));
        assertFalse(SessionDraftPolicy.copyOffered(
                result(doc, 5, VoiceInterval.State.UNDELIVERED, ". "), doc, 5));
        assertFalse(SessionDraftPolicy.copyOffered(
                result(doc, 5, VoiceInterval.State.UNDELIVERED, "Hello."), null, 5));
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

    @Test public void recoveryNeedsALetterOrDigit() {
        assertFalse(SessionDraftPolicy.hasLetterOrDigit(". "));
        assertFalse(SessionDraftPolicy.hasLetterOrDigit(null));
        assertTrue(SessionDraftPolicy.hasLetterOrDigit("Привет"));
        assertEquals(true, SessionDraftPolicy.hasLetterOrDigit("7"));
    }
}
