package dev.notune.transcribe;

import static org.junit.Assert.*;
import org.junit.Test;
import dev.notune.transcribe.TextFitter.FieldKind;

/** Exact text and deletion plans at the pure joining boundary. */
public class PieceJoinerTest {
    private static final class Field {
        final StringBuilder before;
        final PieceJoiner joiner = new PieceJoiner();
        final String after;
        final boolean always;
        final float threshold;

        Field(String before, String after, boolean always, float threshold) {
            this.before = new StringBuilder(before);
            this.after = after;
            this.always = always;
            this.threshold = threshold;
        }

        PieceJoiner.Join say(String raw, float pause) {
            PieceJoiner.Join joined = joiner.join(raw, pause, before, after,
                    FieldKind.PROSE, 0, threshold, always);
            apply(joined);
            return joined;
        }

        void apply(PieceJoiner.Join joined) {
            if (joined.deleteBefore == 1) {
                assertEquals(' ', before.charAt(before.length() - 1));
                before.setLength(before.length() - 1);
            }
            before.append(joined.text);
        }

        String stop(boolean afterStartsSentence) {
            apply(joiner.finish(always, afterStartsSentence));
            return before.toString() + after;
        }
    }

    private static Field field(boolean always) {
        return new Field("", "", always, 3f);
    }

    @Test public void defaultWritesTheSpaceAndKeepsOnlyThePeriodOwed() {
        Field f = field(false);
        assertEquals("Hello ", f.say("Hello.", 0f).text);
        assertEquals("Hello ", f.stop(false));
        assertTrue(f.joiner.hasHeldTail());
        assertEquals(".", f.joiner.pendingTail().mark);
        assertTrue(f.joiner.pendingTail().ownsSpace);
    }

    @Test public void alwaysWritesThePeriodBeforeItsOwnedSpaceAtStop() {
        Field f = field(true);
        f.say("Hello.", 0f);
        PieceJoiner.Join end = f.joiner.finish(true, false);
        assertEquals(1, end.deleteBefore);
        assertEquals(". ", end.text);
        f.apply(end);
        assertEquals("Hello. ", f.before.toString());
        assertFalse(f.joiner.hasHeldTail());
        assertEquals("", f.joiner.finish(true, false).text);
    }

    @Test public void shortPauseContinuesAndLongPauseStartsASentence() {
        for (boolean always : new boolean[]{false, true}) {
            Field shortPause = field(always);
            shortPause.say("First part.", 0f);
            assertEquals(0, shortPause.say("Second part.", 2.9f).deleteBefore);
            assertEquals(always ? "First part second part. " : "First part second part ",
                    shortPause.stop(false));
            Field longPause = field(always);
            longPause.say("First part.", 0f);
            PieceJoiner.Join next = longPause.say("Second part.", 3f);
            assertEquals(1, next.deleteBefore);
            assertEquals(". Second part ", next.text);
            assertEquals(always ? "First part. Second part. " : "First part. Second part ",
                    longPause.stop(false));
        }
    }

    @Test public void customThresholdChangesOnlyTheSentenceBoundary() {
        Field f = new Field("", "", false, 1.5f);
        f.say("I was thinking.", 0f);
        f.say("That we should go home.", 2f);
        assertEquals("I was thinking. That we should go home ", f.stop(false));
        assertEquals(3f, PieceJoiner.DEFAULT_SENTENCE_PAUSE_SECONDS, 0.0001f);
    }

    @Test public void defaultKeepsQuestionsExclamationsAndBothEllipsesImmediately() {
        for (String mark : new String[]{"?", "!", "…", "...", "?!"}) {
            Field f = field(false);
            assertEquals("Really" + mark + " ", f.say("Really" + mark, 0f).text);
            assertFalse(f.joiner.hasHeldTail());
            f.say("Next.", 0.1f);
            assertEquals("Really" + mark + " Next ", f.stop(false));
        }
    }

    @Test public void retainedMarksSurviveLowercaseAndFollowingPunctuation() {
        for (String after : new String[]{"existing", " existing", ", rest", ")"}) {
            for (String mark : new String[]{"?", "!", "…", "..."}) {
                Field f = new Field("", after, false, 3f);
                f.say("Really" + mark, 0f);
                String separator = after.startsWith("existing") ? " " : "";
                assertEquals("Really" + mark + separator + after, f.stop(false));
                assertFalse(f.joiner.hasHeldTail());
            }
        }
    }

    @Test public void alwaysKeepsTheOriginalNonPeriodTerminalRules() {
        for (String mark : new String[]{"?", "!", "…"}) {
            Field f = field(true);
            f.say("Really" + mark, 0f);
            assertTrue(f.joiner.hasHeldTail());
            f.say("Next.", 1f);
            assertEquals("Really next. ", f.stop(false));
            Field lower = new Field("", "existing", true, 3f);
            lower.say("Really" + mark, 0f);
            assertEquals("Really existing", lower.stop(false));
        }
        Field ellipsis = field(true);
        ellipsis.say("Really...", 0f);
        assertEquals("Really... ", ellipsis.stop(false));
    }

    @Test public void internalPeriodsAndUnfinishedWordsStayExact() {
        Field f = field(false);
        f.say("We called Dr. Smith about 3.14.", 0f);
        f.say("And NASA", 1f);
        assertEquals("We called Dr. Smith about 3.14 and NASA ", f.stop(false));
        assertFalse(f.joiner.hasHeldTail());
    }

    @Test public void rightSentenceFinishesButRightContinuationDropsThePeriod() {
        for (boolean always : new boolean[]{false, true}) {
            Field sentence = new Field("", "Existing paragraph", always, 3f);
            sentence.say("The venue is booked.", 0f);
            assertEquals("The venue is booked Existing paragraph", sentence.before + sentence.after);
            assertEquals("The venue is booked. Existing paragraph", sentence.stop(true));
            Field continuation = new Field("", "existing paragraph", always, 3f);
            continuation.say("The venue is booked.", 0f);
            assertFalse(continuation.joiner.hasHeldTail());
            assertEquals("The venue is booked existing paragraph", continuation.stop(false));
        }
    }

    @Test public void existingRightSpaceIsNeverClaimedByVoice() {
        Field f = new Field("", " Existing paragraph", false, 3f);
        f.say("Booked.", 0f);
        assertFalse(f.joiner.pendingTail().ownsSpace);
        PieceJoiner.Join end = f.joiner.finish(false, true);
        assertEquals(0, end.deleteBefore);
        assertEquals(".", end.text);
        f.apply(end);
        assertEquals("Booked. Existing paragraph", f.before + f.after);
    }

    @Test public void longPauseWithoutOwnedSuffixGetsOneNewPrefixSpace() {
        Field f = new Field("", " Existing paragraph", false, 3f);
        f.say("Booked.", 0f);
        PieceJoiner.Join next = f.say("Next.", 4f);
        assertEquals(0, next.deleteBefore);
        assertEquals(". Next", next.text);
        assertEquals("Booked. Next. Existing paragraph", f.stop(true));
    }

    @Test public void movingAwayDropsOnlyTheMarkAndKeepsTheF1Separator() {
        Field f = new Field("", "Existing paragraph", false, 3f);
        f.say("The venue is booked.", 0f);
        PieceJoiner.PendingTail abandoned = f.joiner.abandonHeldTail();
        assertEquals(".", abandoned.mark);
        assertTrue(abandoned.ownsSpace);
        assertFalse(f.joiner.hasHeldTail());
        assertEquals("The venue is booked Existing paragraph", f.stop(true));
    }

    @Test public void frontierStopKeepsTheMarkAcrossRecordingRestart() {
        for (float gap : new float[]{1f, 3f}) {
            Field f = field(false);
            f.say("First.", 0f);
            assertEquals("First ", f.stop(false));
            f.say("Second.", gap);
            assertEquals(gap < 3f ? "First second " : "First. Second ", f.stop(false));
        }
    }

    @Test public void rollbackRestoresBothTheMarkAndTheOwnedSpace() {
        Field f = field(false);
        f.say("Hello.", 0f);
        PieceJoiner.PendingTail saved = f.joiner.pendingTail();
        f.joiner.abandonHeldTail();
        f.joiner.restorePendingTail(saved);
        f.apply(f.joiner.finish(true, false));
        assertEquals("Hello. ", f.before.toString());
        f.joiner.restorePendingTail(null);
        assertFalse(f.joiner.hasHeldTail());
    }

    @Test public void emptyPieceDoesNotConsumeThePendingPeriod() {
        Field f = field(false);
        f.say("Hello.", 0f);
        assertEquals("", f.say(" ", 5f).text);
        assertEquals("", f.say(null, 5f).text);
        assertTrue(f.joiner.hasHeldTail());
        assertEquals("Hello ", f.stop(false));
    }

    @Test public void unreadableDefaultDropsOnlyASinglePeriodWithoutHolding() {
        for (boolean always : new boolean[]{false, true}) {
            PieceJoiner j = new PieceJoiner();
            StringBuilder out = new StringBuilder();
            for (String raw : new String[]{"Hello.", "Really?", "Wait!", "Maybe...", "Perhaps…"}) {
                PieceJoiner.Join join = j.join(raw, 4f, null, null, FieldKind.PROSE, 0, 3f, always);
                assertEquals(0, join.deleteBefore);
                out.append(join.text);
                assertFalse(j.hasHeldTail());
            }
            assertEquals(always ? "Hello. Really? Wait! Maybe... Perhaps… "
                                : "Hello Really? Wait! Maybe... Perhaps… ", out.toString());
        }
    }

    @Test public void newlineRightTextIsAFrontierWithoutAnOwnedSpace() {
        Field f = new Field("", "\nnext line", false, 3f);
        f.say("Booked.", 0f);
        assertFalse(f.joiner.pendingTail().ownsSpace);
        assertEquals("Booked\nnext line", f.stop(false));
        assertTrue(f.joiner.hasHeldTail());
    }

    @Test public void fieldKindsKeepTheirExistingRulesInEitherMode() {
        for (boolean always : new boolean[]{false, true}) {
            for (FieldKind kind : new FieldKind[]{FieldKind.SEARCH, FieldKind.PLAIN,
                                                FieldKind.PASSWORD, FieldKind.NUMBER}) {
                PieceJoiner j = new PieceJoiner();
                String raw = kind == FieldKind.NUMBER ? "12." : "Hello.";
                PieceJoiner.Join join = j.join(raw, 0f, "", "", kind, 0, 3f, always);
                assertEquals(kind == FieldKind.NUMBER ? "12"
                        : kind == FieldKind.PASSWORD ? "Hello." : "Hello", join.text);
                assertEquals(0, join.deleteBefore);
                assertFalse(j.hasHeldTail());
            }
        }
    }
}
