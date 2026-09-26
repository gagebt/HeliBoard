package dev.notune.transcribe;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import org.junit.Test;

import dev.notune.transcribe.TextFitter.FieldKind;

/** X1 and X2: which rules a field gets, and what a number field may receive. */
public class FieldRulesTest {
    private static final int TEXT = InputType.TYPE_CLASS_TEXT;
    private static final int NUMBER = InputType.TYPE_CLASS_NUMBER;

    @Test public void terminalGetsProseAndIsMarkedUnreadable() {
        assertEquals(FieldKind.PROSE, FieldKinds.of(InputType.TYPE_NULL, 0x2000000));
        EditorRecord termux = new EditorRecord("com.termux", 2131231121, InputType.TYPE_NULL,
                0x2000000, false);
        assertFalse(termux.readBackKnown);
        assertTrue(new EditorRecord("a", 1, TEXT, 0, false).readBackKnown);
    }

    @Test public void termuxCharModeIsAnUnreadableTerminalNotASecret() {
        // Observed on the emulator with enforce-char-based-input=true: 0x80090.
        EditorRecord termux = new EditorRecord("com.termux", 2131231121, 0x80090,
                0x2000000, false);
        assertEquals(FieldKind.PROSE, termux.kind);
        assertFalse(termux.readBackKnown);
        assertFalse(termux.privateField);
        int visiblePassword = TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS;
        assertFalse(new EditorRecord("com.termux", 2131231121, visiblePassword, 0, false)
                .privateField);
        // Opposite: the same type in any other app is a password field.
        EditorRecord other = new EditorRecord("bank.app", 7, visiblePassword, 0, false);
        assertEquals(FieldKind.PASSWORD, other.kind);
        assertTrue(other.privateField);
        assertTrue(other.readBackKnown);
    }

    @Test public void addressAndFilterFieldsGetSearchSpacingButEmailStaysPlain() {
        assertEquals(FieldKind.SEARCH, FieldKinds.of(TEXT | InputType.TYPE_TEXT_VARIATION_URI, 0));
        assertEquals(FieldKind.SEARCH, FieldKinds.of(TEXT | InputType.TYPE_TEXT_VARIATION_FILTER, 0));
        assertEquals(FieldKind.PLAIN,
                FieldKinds.of(TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS, 0));
        assertEquals(FieldKind.PROSE, FieldKinds.of(TEXT, 0));
    }

    @Test public void numberFieldsAndNumericPins() {
        assertEquals(FieldKind.NUMBER, FieldKinds.of(NUMBER, 0));
        assertEquals(FieldKind.PASSWORD,
                FieldKinds.of(NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD, 0));
    }

    @Test public void terminalPiecesAreSeparatedWhereThePf5RuleJoinedThem() {
        PieceJoiner j = new PieceJoiner();
        StringBuilder out = new StringBuilder();
        out.append(j.join("Hi Sam, I will be 10 minutes late.", 0f, null, null,
                FieldKinds.of(InputType.TYPE_NULL, 0), 0, 3f));
        out.append(j.join("We can meet at the east entrance.", 3.5f, null, null,
                FieldKinds.of(InputType.TYPE_NULL, 0), 0, 3f));
        out.append(j.finish());
        assertEquals("Hi Sam, I will be 10 minutes late. We can meet at the east entrance. ",
                out.toString());
        // Opposite: the plain rule, which pf5 gave a terminal, joins the two sentences.
        PieceJoiner plain = new PieceJoiner();
        String joined = plain.join("late.", 0f, null, null, FieldKind.PLAIN, 0, 3f)
                + plain.join("We can.", 3.5f, null, null, FieldKind.PLAIN, 0, 3f);
        assertEquals("lateWe can", joined);
    }

    @Test public void addressBarPiecesKeepOneSpace() {
        PieceJoiner j = new PieceJoiner();
        String first = j.join("Weather in Moscow tomorrow.", 0f, "", "", FieldKind.SEARCH, 0, 3f);
        String second = j.join("The forecast.", 1f, first, "", FieldKind.SEARCH, 0, 3f);
        assertEquals("Weather in Moscow tomorrow The forecast", first + second);
    }

    @Test public void spokenNumbersBecomeDigitsOnlyWhenAllOfThePieceIsANumber() {
        assertEquals("12", TextFitter.numberText("12."));
        assertEquals("-7", TextFitter.numberText("-7"));
        assertEquals("1500", TextFitter.numberText("1,500"));
        assertEquals("3.5", TextFitter.numberText("3,5"));
        assertEquals("2.25", TextFitter.numberText("2.25"));
        assertEquals("", TextFitter.numberText("1/2"));
        assertEquals("", TextFitter.numberText("twelve"));
        assertEquals("", TextFitter.numberText("12e"));
        assertEquals("", TextFitter.numberText("12 apples"));
        assertEquals("", TextFitter.fit("1/2", "", "", FieldKind.NUMBER, 0).inserted());
    }

    @Test public void theWholeFieldValueMustBeANumberTheFieldAllows() {
        int decimal = NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL;
        int signed = NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
        assertTrue(FieldKinds.numberValueFits(NUMBER, "12"));
        assertFalse(FieldKinds.numberValueFits(NUMBER, "-12"));
        assertTrue(FieldKinds.numberValueFits(signed, "-12"));
        assertFalse(FieldKinds.numberValueFits(NUMBER, "3.5"));
        assertTrue(FieldKinds.numberValueFits(decimal, "3.5"));
        // A field that already holds a decimal gets no second separator.
        assertFalse(FieldKinds.numberValueFits(decimal, "3.5" + "2.5"));
        assertFalse(FieldKinds.numberValueFits(signed, "4-2"));
        assertFalse(FieldKinds.numberValueFits(NUMBER, ""));
    }

    @Test public void privacyIsOnlyFieldFactsAndIncognito() {
        assertTrue(FieldKinds.isPrivate(TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 0, false));
        assertTrue(FieldKinds.isPrivate(TEXT, EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
        assertTrue(FieldKinds.isPrivate(TEXT, 0, true));
        assertFalse(FieldKinds.isPrivate(TEXT, 0, false));
        assertFalse(FieldKinds.isPrivate(InputType.TYPE_NULL, 0, false));
    }
}
