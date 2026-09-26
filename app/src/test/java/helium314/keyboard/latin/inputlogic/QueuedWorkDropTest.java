// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.latin.inputlogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import helium314.keyboard.latin.LatinIME;
import helium314.keyboard.latin.Suggest;
import helium314.keyboard.latin.SuggestedWords;
import helium314.keyboard.latin.WordComposer;
import helium314.keyboard.latin.common.InputPointers;
import helium314.keyboard.latin.dictionary.Dictionary;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** X10 (d): a queued prediction that has not started is dropped when a gesture starts; the gesture still runs. */
@RunWith(RobolectricTestRunner.class)
public class QueuedWorkDropTest {
    @Test public void gestureStartDropsQueuedPredictionButKeepsGestureWork() throws Exception {
        final LatinIME.UIHandler ui = mock(LatinIME.UIHandler.class);
        final InputLogic logic = mock(InputLogic.class);
        final Field composer = InputLogic.class.getDeclaredField("mWordComposer");
        composer.setAccessible(true);
        composer.set(logic, new WordComposer());
        doAnswer(call -> {
            final int sequence = call.getArgument(1);
            final Suggest.OnGetSuggestedWordsCallback callback = call.getArgument(3);
            callback.onGetSuggestedWords(new SuggestedWords(
                    new ArrayList<>(List.of(new SuggestedWords.SuggestedWordInfo(
                            "alpha", "", 100, SuggestedWords.SuggestedWordInfo.KIND_CORRECTION,
                            Dictionary.DICTIONARY_APPLICATION_DEFINED,
                            SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX,
                            SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE))),
                    null, null, true, false, false, SuggestedWords.INPUT_STYLE_TAIL_BATCH, sequence));
            return null;
        }).when(logic).getSuggestedWords(anyInt(), anyInt(), any(WordComposer.class), any());

        final InputLogicHandler handler = new InputLogicHandler(ui, logic);
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final CountDownLatch workerFinished = new CountDownLatch(1);
        final AtomicInteger queuedPredictionRuns = new AtomicInteger();
        try {
            // A running prediction occupies the worker; a second one waits in the queue.
            handler.getSuggestedWords(() -> {
                workerEntered.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            handler.getSuggestedWords(queuedPredictionRuns::incrementAndGet);

            final InputPointers points = new InputPointers(1);
            points.addPointer(10, 20, 0, 0);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(points, 1);
            releaseWorker.countDown();
            handler.getSuggestedWords(workerFinished::countDown);
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));

            assertEquals("the queued prediction must not run before the gesture", 0, queuedPredictionRuns.get());
            verify(ui, times(1)).showTailBatchInputResult(any());
        } finally {
            releaseWorker.countDown();
            handler.mNonUIThreadHandler.getLooper().quitSafely();
        }
    }
}
