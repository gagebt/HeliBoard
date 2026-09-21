package dev.notune.transcribe;

/** Keeps older unresolved recordings separate from the current delivery. */
final class RecoveryText {
    private RecoveryText() { }

    static String joinRecords(String older, String newer) {
        String left = older == null ? "" : older;
        String right = newer == null ? "" : newer;
        if (left.isEmpty()) return right;
        if (right.isEmpty()) return left;
        return left + "\n" + right;
    }

    static String settled(String older, String current, boolean currentNeedsRecovery) {
        return currentNeedsRecovery ? joinRecords(older, current)
                : older == null ? "" : older;
    }
}


