package dev.notune.transcribe;

/** Pure punctuation state for pieces delivered at one unchanged editor position. */
public final class PieceJoiner {
    public static final float DEFAULT_SENTENCE_PAUSE_SECONDS = 3.0f;

    /** The caller deletes only a proved voice-owned space before committing text. */
    public static final class Join {
        public final int deleteBefore;
        public final String text;

        Join(int deleteBefore, String text) {
            this.deleteBefore = deleteBefore;
            this.text = text;
        }
    }

    /** Immutable rollback snapshot. The separator is already written, not held. */
    public static final class PendingTail {
        public final String mark;
        public final boolean ownsSpace;

        PendingTail(String mark, boolean ownsSpace) {
            this.mark = mark;
            this.ownsSpace = ownsSpace;
        }
    }

    private static final Join NOTHING = new Join(0, "");
    private static final PendingTail NO_TAIL = new PendingTail("", false);
    private PendingTail heldTail = NO_TAIL;

    static int capsModeForPiece(int initialCapsMode, boolean hasAcceptedPiece) {
        return hasAcceptedPiece ? 0 : initialCapsMode;
    }

    public Join join(String raw, float pauseBeforeSeconds, CharSequence before,
                     CharSequence after, TextFitter.FieldKind kind, int capsMode,
                     float sentencePauseSeconds, boolean alwaysFullStop) {
        if (raw == null || raw.trim().isEmpty()) return NOTHING;

        Join emit = NOTHING;
        if (hasHeldTail()) {
            if (before != null && pauseBeforeSeconds >= sentencePauseSeconds) {
                emit = emitHeldTail();
            } else {
                abandonHeldTail();
            }
        }
        CharSequence effectiveBefore = before;
        if (before != null && !emit.text.isEmpty()) {
            effectiveBefore = before.subSequence(0, before.length() - emit.deleteBefore)
                    .toString() + emit.text;
        }
        TextFitter.Fit fit = TextFitter.fit(raw, effectiveBefore, after, kind, capsMode,
                alwaysFullStop);
        String text = fit.text;
        if (kind == TextFitter.FieldKind.PROSE && before != null && text.length() >= 2
                && (alwaysFullStop ? TextFitter.endsWithTerminator(text)
                                  : TextFitter.endsWithOrdinaryFullStop(text))) {
            heldTail = new PendingTail(text.substring(text.length() - 1),
                    fit.suffix.equals(" "));
            text = text.substring(0, text.length() - 1);
        }
        return new Join(emit.deleteBefore, emit.text + fit.prefix + text + fit.suffix);
    }

    /** Default frontier keeps the owed period for a later recording at the same spot. */
    public Join finish(boolean alwaysFullStop, boolean afterStartsSentence) {
        return hasHeldTail() && (alwaysFullStop || afterStartsSentence)
                ? emitHeldTail() : NOTHING;
    }

    private Join emitHeldTail() {
        PendingTail tail = abandonHeldTail();
        return new Join(tail.ownsSpace ? 1 : 0, tail.mark + (tail.ownsSpace ? " " : ""));
    }

    public boolean hasHeldTail() {
        return !heldTail.mark.isEmpty();
    }

    PendingTail abandonHeldTail() {
        PendingTail tail = heldTail;
        heldTail = NO_TAIL;
        return tail;
    }

    PendingTail pendingTail() {
        return heldTail;
    }

    void restorePendingTail(PendingTail tail) {
        heldTail = tail == null ? NO_TAIL : tail;
    }
}
