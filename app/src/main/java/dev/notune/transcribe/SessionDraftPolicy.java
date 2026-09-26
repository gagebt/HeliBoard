package dev.notune.transcribe;

/** Pure decisions at voice-session boundaries; a saved draft never becomes an auto-replay queue. */
final class SessionDraftPolicy {
    private SessionDraftPolicy() { }

    static boolean deliveryOpenAfterUnconfirmedCommit(boolean accepted) {
        return accepted;
    }

    /**
     * True when a field that just started continues the binding voice was writing into.
     * The field record must match, and then either the keyboard is rotating or the text on
     * both sides of the cursor and the cursor itself read exactly as voice last left them.
     * A widget id shared by many documents, or a field that cannot be read, is ambiguous and
     * never continues a binding.
     */
    static boolean continuesBinding(EditorRecord target, EditorRecord current, boolean rotating,
                                    String expectedBefore, String expectedAfter,
                                    int expectedSelectionStart, int expectedSelectionEnd,
                                    String currentBefore, String currentAfter,
                                    int currentSelectionStart, int currentSelectionEnd) {
        if (target == null || !target.sameField(current)) return false;
        if (rotating) return true;
        if (!current.readBackKnown || expectedBefore == null || expectedAfter == null
                || currentBefore == null || currentAfter == null) return false;
        if (expectedSelectionStart >= 0 && currentSelectionStart >= 0
                && (expectedSelectionStart != currentSelectionStart
                || expectedSelectionEnd != currentSelectionEnd)) return false;
        return expectedBefore.equals(currentBefore) && expectedAfter.equals(currentAfter);
    }

    /** Recovery text worth keeping: a lone ". " left by the final mark is not. */
    static boolean hasLetterOrDigit(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            if (Character.isLetterOrDigit(cp)) return true;
            i += Character.charCount(cp);
        }
        return false;
    }

    /**
     * Copy rule (X3). Unconfirmed text is offered only in the binding it was spoken into.
     * Undelivered text is offered in that binding, and in any other ordinary field unless
     * it came from a private field. Never in another private field, never without a letter
     * or digit. Optional history settings are not an input.
     */
    static boolean copyOffered(VoiceInterval interval, EditorRecord current, long currentBinding) {
        if (interval == null || current == null || !hasLetterOrDigit(interval.text)) return false;
        boolean own = interval.binding >= 0 && interval.binding == currentBinding;
        if (interval.state == VoiceInterval.State.UNCONFIRMED) return own;
        if (interval.state != VoiceInterval.State.UNDELIVERED) return false;
        return own || !interval.privateOrigin() && !current.privateField;
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
