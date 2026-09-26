/*
 * Copyright (C) 2013 The Android Open Source Project
 * modified
 * SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
 */

package helium314.keyboard.latin.inputlogic;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.os.SystemClock;

import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.utils.Log;

import java.util.ArrayList;
import java.util.HashSet;

/**
 * A helper to manage deferred tasks for the input logic.
 */
class InputLogicHandler implements Handler.Callback {
    private static final String TAG = InputLogicHandler.class.getSimpleName();
    final Handler mNonUIThreadHandler;
    final LatinIME.UIHandler mLatinIMEHandler;
    final InputLogic mInputLogic;
    private final Object mLock = new Object();
    private boolean mInBatchInput; // synchronized using {@link #mLock}.
    private long mBatchGeneration;
    private long mTimedUpdateGeneration = -1; // synchronized using {@link #mLock}.
    private final HashSet<Long> mCompletedBatchGenerations = new HashSet<>();

    // Gesture work; never dropped by a new gesture.
    private static final int MSG_GET_SUGGESTED_WORDS = 1;
    // Strip, recorrection and other work; queued jobs that have not started are dropped when a gesture starts.
    private static final int MSG_DROPPABLE_WORK = 2;

    public InputLogicHandler(final LatinIME.UIHandler latinIMEHandler, final InputLogic inputLogic) {
        final HandlerThread handlerThread = new HandlerThread(
                InputLogicHandler.class.getSimpleName());
        handlerThread.start();
        mNonUIThreadHandler = new Handler(handlerThread.getLooper(), this);
        mLatinIMEHandler = latinIMEHandler;
        mInputLogic = inputLogic;
    }

    public void reset() {
        synchronized (mLock) {
            mInBatchInput = false;
            ++mBatchGeneration;
            mCompletedBatchGenerations.clear();
        }
        mNonUIThreadHandler.removeCallbacksAndMessages(null);
    }

    /**
     * Handle a message.
     * @see android.os.Handler.Callback#handleMessage(android.os.Message)
     */
    // Called on the Non-UI handler thread by the Handler code.
    @Override
    public boolean handleMessage(final Message msg) {
        if (msg.what == MSG_GET_SUGGESTED_WORDS || msg.what == MSG_DROPPABLE_WORK)
            ((Runnable)msg.obj).run();
        return true;
    }

    // Called on the UI thread by InputLogic.
    public void onStartBatchInput() {
        synchronized (mLock) {
            mInBatchInput = true;
            ++mBatchGeneration;
        }
        // A queued suggestion-strip or prediction job would only delay the gesture; its result is replaced anyway.
        if (mNonUIThreadHandler.hasMessages(MSG_DROPPABLE_WORK)) {
            mNonUIThreadHandler.removeMessages(MSG_DROPPABLE_WORK);
            Log.i(TAG, "gesture start dropped queued suggestion work");
        }
    }

    public boolean isInBatchInput() {
        synchronized (mLock) {
            return mInBatchInput;
        }
    }

    /**
     * Fetch suggestions corresponding to an update of a batch input.
     * @param batchPointers the updated pointers, including the part that was passed last time.
     * @param sequenceNumber the sequence number associated with this batch input.
     * @param isTailBatchInput true if this is the end of a batch input, false if it's an update.
     */
    // This method can be called from any thread and will see to it that the correct threads
    // are used for parts that require it. This method will send a message to the Non-UI handler
    // thread to pull suggestions, and get the inlined callback to get called on the Non-UI
    // handler thread. If this is the end of a batch input, the callback will then proceed to
    // send a message to the UI handler in LatinIME so that showing suggestions can be done on
    // the UI thread.
    private void updateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber, final boolean isTailBatchInput) {
        synchronized (mLock) {
            if (!mInBatchInput) {
                // Batch input has ended or canceled while the message was being delivered.
                return;
            }
            final long batchGeneration = mBatchGeneration;
            mInputLogic.mWordComposer.setBatchInputPointers(batchPointers);
            final WordComposer wordComposer = mInputLogic.mWordComposer.copyForBatchInput(batchPointers);
            if (isTailBatchInput) mCompletedBatchGenerations.add(batchGeneration);
            final long queuedAt = SystemClock.uptimeMillis();
            final int points = batchPointers.getPointerSize();
            final boolean timed = isTailBatchInput || mTimedUpdateGeneration != batchGeneration;
            if (!isTailBatchInput) mTimedUpdateGeneration = batchGeneration;
            mNonUIThreadHandler.obtainMessage(MSG_GET_SUGGESTED_WORDS, (Runnable) () -> {
                synchronized (mLock) {
                    if (isTailBatchInput) {
                        if (!mCompletedBatchGenerations.contains(batchGeneration)) return;
                    } else if (!mInBatchInput || mBatchGeneration != batchGeneration) {
                        return;
                    }
                }
                final long startedAt = SystemClock.uptimeMillis();
                mInputLogic.getSuggestedWords(
                    isTailBatchInput ? SuggestedWords.INPUT_STYLE_TAIL_BATCH : SuggestedWords.INPUT_STYLE_UPDATE_BATCH, sequenceNumber, wordComposer,
                    suggestedWords -> {
                        if (timed) {
                            // Numbers only: never the gesture's text.
                            Log.i(TAG, "gesture " + (isTailBatchInput ? "tail" : "first update") + " timing: points="
                                    + points + " queueMs=" + (startedAt - queuedAt)
                                    + " workMs=" + (SystemClock.uptimeMillis() - startedAt)
                                    + " candidates=" + suggestedWords.size());
                        }
                        showGestureSuggestionsWithPreviewVisuals(suggestedWords, isTailBatchInput, batchGeneration, sequenceNumber);
                    });
            }).sendToTarget();
        }
    }

    void showGestureSuggestionsWithPreviewVisuals(final SuggestedWords suggestedWordsForBatchInput,
            final boolean isTailBatchInput, final long batchGeneration, final int sequenceNumber) {
        final boolean isCurrentGesture;
        synchronized (mLock) {
            isCurrentGesture = mInBatchInput && mBatchGeneration == batchGeneration;
            if (isTailBatchInput) {
                if (!mCompletedBatchGenerations.remove(batchGeneration) && !isCurrentGesture) return;
                if (isCurrentGesture) mInBatchInput = false;
            } else if (!isCurrentGesture) {
                return;
            }
        }
        final SuggestedWords suggestedWordsToShowSuggestions;
        // We're now inside the callback. This always runs on the Non-UI thread,
        // no matter what thread updateBatchInput was originally called on.
        if (suggestedWordsForBatchInput.isEmpty()) {
            // A failed tail must still end this gesture, without committing an unrelated old word.
            suggestedWordsToShowSuggestions = isTailBatchInput
                    ? new SuggestedWords(new ArrayList<>(), null, null, false, false, false,
                            SuggestedWords.INPUT_STYLE_TAIL_BATCH, sequenceNumber)
                    : mInputLogic.mSuggestedWords;
        } else {
            suggestedWordsToShowSuggestions = suggestedWordsForBatchInput;
        }
        if (isCurrentGesture) {
            mLatinIMEHandler.showGesturePreviewAndSetSuggestions(suggestedWordsToShowSuggestions, isTailBatchInput);
        }
        if (isTailBatchInput) {
            // The following call schedules onEndBatchInputInternal
            // to be called on the UI thread.
            mLatinIMEHandler.showTailBatchInputResult(suggestedWordsToShowSuggestions);
        }
    }

    /**
     * Update a batch input.
     * <p>
     * This fetches suggestions and updates the suggestion strip and the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void onUpdateBatchInput(final InputPointers batchPointers,
            final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, false);
    }

    /**
     * Cancel a batch input.
     * <p>
     * Note that as opposed to updateTailBatchInput, we do the UI side of this immediately on the
     * same thread, rather than get this to call a method in LatinIME. This is because
     * canceling a batch input does not necessitate the long operation of pulling suggestions.
     */
    // Called on the UI thread by InputLogic.
    public boolean onCancelBatchInput() {
        synchronized (mLock) {
            mInBatchInput = false;
            final boolean canceledCompletedTail = mCompletedBatchGenerations.remove(mBatchGeneration);
            ++mBatchGeneration;
            return canceledCompletedTail;
        }
    }

    /**
     * Trigger an update for a tail batch input.
     * <p>
     * A tail batch input is the last update for a gesture, the one that is triggered after the
     * user lifts their finger. This method schedules fetching suggestions on the non-UI thread,
     * then when the suggestions are computed it comes back on the UI thread to update the
     * suggestion strip, commit the first suggestion, and dismiss the floating text preview.
     *
     * @param batchPointers the updated batch pointers.
     * @param sequenceNumber the sequence number associated with this batch input.
     */
    // Called on the UI thread by InputLogic.
    public void updateTailBatchInput(final InputPointers batchPointers,
            final int sequenceNumber) {
        updateBatchInput(batchPointers, sequenceNumber, true);
    }

    public void getSuggestedWords(final Runnable callback) {
        mNonUIThreadHandler.obtainMessage(MSG_DROPPABLE_WORK, callback).sendToTarget();
    }
}
