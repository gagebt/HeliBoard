package dev.notune.transcribe;

import android.text.InputType;
import android.view.inputmethod.EditorInfo;

import helium314.keyboard.latin.utils.InputTypeUtils;

/**
 * Reads {@link EditorInfo} and says how much sentence logic the field gets. This is the
 * only place the Android field constants meet {@link TextFitter}, which stays free of
 * Android types.
 */
final class FieldKinds {

    private FieldKinds() { }

    static TextFitter.FieldKind of(int type, int imeOptions) {
        // A terminal (Termux) reports TYPE_NULL. It holds prose; its text before the
        // cursor cannot be read back, which EditorRecord.readBackKnown records.
        if (type == InputType.TYPE_NULL) return TextFitter.FieldKind.PROSE;

        int cls = type & InputType.TYPE_MASK_CLASS;
        int variation = type & InputType.TYPE_MASK_VARIATION;

        if (cls == InputType.TYPE_CLASS_TEXT) {
            switch (variation) {
                case InputType.TYPE_TEXT_VARIATION_PASSWORD:
                case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
                case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
                    return TextFitter.FieldKind.PASSWORD;
                case InputType.TYPE_TEXT_VARIATION_URI:
                case InputType.TYPE_TEXT_VARIATION_FILTER:
                    // Address bars and filter boxes join pieces with one space, as search does.
                    return TextFitter.FieldKind.SEARCH;
                case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
                case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
                    return TextFitter.FieldKind.PLAIN;
                default:
                    break;
            }
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            // A numeric PIN is masked like any other password: change nothing at all.
            if (variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD) {
                return TextFitter.FieldKind.PASSWORD;
            }
            return TextFitter.FieldKind.NUMBER;
        } else if (cls == InputType.TYPE_CLASS_PHONE || cls == InputType.TYPE_CLASS_DATETIME) {
            return TextFitter.FieldKind.PLAIN;
        }

        // A search box gets "Weather in Moscow", not "Weather in Moscow. ".
        if ((imeOptions & EditorInfo.IME_MASK_ACTION) == EditorInfo.IME_ACTION_SEARCH) {
            return TextFitter.FieldKind.SEARCH;
        }

        // Everything else, including "no suggestions" and multi-line fields.
        return TextFitter.FieldKind.PROSE;
    }

    /**
     * True when the whole field value, after the insert, is one number of the form the
     * field allows: digits, a leading minus only with {@code TYPE_NUMBER_FLAG_SIGNED}, and
     * one decimal point only with {@code TYPE_NUMBER_FLAG_DECIMAL}. A second point, a sign
     * in the middle or a sign the field refuses all fail.
     */
    static boolean numberValueFits(int type, String value) {
        String sign = (type & InputType.TYPE_NUMBER_FLAG_SIGNED) != 0 ? "-?" : "";
        String decimal = (type & InputType.TYPE_NUMBER_FLAG_DECIMAL) != 0 ? "(\\.\\d*)?" : "";
        return value.matches(sign + "\\d+" + decimal);
    }

    /**
     * The one privacy predicate: a password field, a field that asks for no personalised
     * learning, or the keyboard's incognito mode. Such a field gets no draft file, no Copy
     * and no history entry. History settings are not part of it.
     */
    static boolean isPrivate(int type, int imeOptions, boolean incognito) {
        if (incognito) return true;
        return (imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                || InputTypeUtils.isAnyPasswordInputType(type);
    }
}
