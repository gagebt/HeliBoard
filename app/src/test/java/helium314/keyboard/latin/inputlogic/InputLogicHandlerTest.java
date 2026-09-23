// SPDX-License-Identifier: Apache-2.0 AND GPL-3.0-only
package helium314.keyboard.latin.inputlogic;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.SuggestedWords;

import java.lang.reflect.Field;
import java.util.ArrayList;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class InputLogicHandlerTest {
    @Test
    public void delayedTailCannotEndOrPublishNewerGesture() throws Exception {
        final LatinIME.UIHandler ui = mock(LatinIME.UIHandler.class);
        final InputLogicHandler handler = new InputLogicHandler(ui, mock(InputLogic.class));
        try {
            final SuggestedWords words = new SuggestedWords(
                    new ArrayList<>(java.util.List.of(mock(SuggestedWords.SuggestedWordInfo.class))),
                    null, null, true, false, false, SuggestedWords.INPUT_STYLE_TAIL_BATCH, 1);
            handler.onStartBatchInput();
            final long oldGeneration = generation(handler);
            handler.onStartBatchInput();
            final long currentGeneration = generation(handler);

            handler.showGestureSuggestionsWithPreviewVisuals(words, true, oldGeneration, 1);
            assertTrue(handler.isInBatchInput());
            verifyNoInteractions(ui);

            handler.showGestureSuggestionsWithPreviewVisuals(words, true, currentGeneration, 2);
            assertFalse(handler.isInBatchInput());
            verify(ui).showTailBatchInputResult(words);

            handler.onStartBatchInput();
            handler.showGestureSuggestionsWithPreviewVisuals(
                    SuggestedWords.getEmptyInstance(), true, generation(handler), 3);
            assertFalse(handler.isInBatchInput());
            verify(ui).showTailBatchInputResult(argThat(result -> result.isEmpty()
                    && result.mSequenceNumber == 3));
        } finally {
            handler.mNonUIThreadHandler.getLooper().quitSafely();
        }
    }

    private static long generation(final InputLogicHandler handler) throws Exception {
        final Field field = InputLogicHandler.class.getDeclaredField("mBatchGeneration");
        field.setAccessible(true);
        return field.getLong(handler);
    }
}
