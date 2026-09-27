package dev.notune.transcribe;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import java.util.Objects;

/**
 * The field a dictation belongs to (seam S1). HeliBoard builds one at each
 * {@code onStartInput} and refreshes its Incognito fact on a setting change; delivery, Copy and the draft file
 * read only this record. {@link #sameField} (same package, known field id, same input
 * type) is only a precondition: apps reuse one widget id for many documents, so the
 * controller also keeps a binding generation and continues a binding only after a
 * rotation or an exact match of the text on both sides of the cursor.
 */
public final class EditorRecord {
    public final String packageName;
    /** 0 or -1 (no id) means "unknown": such a field is never the same as another. */
    public final int fieldId;
    public final int inputType;
    public final int imeOptions;
    public final TextFitter.FieldKind kind;
    /** False for a terminal: the text before the cursor cannot be read back. */
    public final boolean readBackKnown;
    /** Password, no-learning or incognito: no draft file or history; Copy stays in its field. */
    public final boolean privateField;
    /**
     * A window without a text field, such as the launcher after Home (observed: type 0,
     * options 0, field id 0). It neither ends a dictation's binding nor receives its words.
     */
    public final boolean noField;

    EditorRecord(String packageName, int fieldId, int inputType, int imeOptions,
                 boolean incognito) {
        this.packageName = packageName;
        this.fieldId = fieldId;
        this.inputType = inputType;
        this.imeOptions = imeOptions;
        this.noField = inputType == InputType.TYPE_NULL && (fieldId == 0 || fieldId == -1);
        boolean terminal = isTerminal(packageName, inputType);
        this.kind = terminal ? TextFitter.FieldKind.PROSE : FieldKinds.of(inputType, imeOptions);
        this.readBackKnown = !terminal;
        // A terminal's visible-password type asks only for raw keys; it is not a secret.
        this.privateField = terminal
                ? FieldKinds.isPrivate(InputType.TYPE_NULL, imeOptions, incognito)
                : FieldKinds.isPrivate(inputType, imeOptions, incognito);
    }

    /**
     * A terminal: any TYPE_NULL field, or Termux in its "Enforce char based input" mode,
     * which reports the visible-password variation instead of TYPE_NULL (observed
     * 0x80090: that variation and no-suggestions, without a class).
     */
    static boolean isTerminal(String packageName, int inputType) {
        if (inputType == InputType.TYPE_NULL) return true;
        int cls = inputType & InputType.TYPE_MASK_CLASS;
        return "com.termux".equals(packageName)
                && (cls == 0 || cls == InputType.TYPE_CLASS_TEXT)
                && (inputType & InputType.TYPE_MASK_VARIATION)
                        == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD;
    }

    public static EditorRecord of(EditorInfo info, boolean incognito) {
        return info == null ? null : new EditorRecord(info.packageName, info.fieldId,
                info.inputType, info.imeOptions, incognito);
    }

    /** Refresh the global setting without replacing the observed field identity. */
    public EditorRecord withIncognito(boolean enabled) {
        return new EditorRecord(packageName, fieldId, inputType, imeOptions, enabled);
    }

    public boolean sameField(EditorRecord other) {
        return other != null && fieldId != 0 && fieldId != -1 && fieldId == other.fieldId
                && inputType == other.inputType && Objects.equals(packageName, other.packageName);
    }

    /** For a number field: true when the whole value after an insert is a number it allows. */
    boolean acceptsNumberValue(String value) {
        return FieldKinds.numberValueFits(inputType, value);
    }

    @Override public String toString() {
        return "EditorRecord[" + packageName + " id=" + fieldId + " type=0x"
                + Integer.toHexString(inputType) + " " + kind
                + (readBackKnown ? "" : " unreadable") + (privateField ? " private" : "")
                + (noField ? " no-field" : "") + "]";
    }
}
