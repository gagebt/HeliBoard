/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.keyboard.internal;

import helium314.keyboard.accessibility.AccessibilityUtils;

public final class GestureEnabler {
    private volatile boolean mMainDictionaryAvailable;
    private boolean mFutoGestureAvailable;
    private boolean mGestureHandlingEnabledByInputField;
    private boolean mGestureHandlingEnabledByUser;

    // Note that this method is called from a non-UI thread.
    public void setMainDictionaryAvailability(final boolean mainDictionaryAvailable) {
        mMainDictionaryAvailable = mainDictionaryAvailable;
    }

    public void setFutoGestureAvailability(final boolean available) {
        mFutoGestureAvailable = available;
    }

    public void setGestureHandlingEnabledByUser(final boolean gestureHandlingEnabledByUser) {
        mGestureHandlingEnabledByUser = gestureHandlingEnabledByUser;
    }

    public void setPasswordMode(final boolean passwordMode) {
        mGestureHandlingEnabledByInputField = !passwordMode;
    }

    public boolean shouldHandleGesture() {
        return (mMainDictionaryAvailable || mFutoGestureAvailable)
                && mGestureHandlingEnabledByInputField
                && mGestureHandlingEnabledByUser
                && !AccessibilityUtils.Companion.getInstance().isTouchExplorationEnabled();
    }
}
