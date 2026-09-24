// Queued gesture ownership regression; run in the isolated stage before integration.
package helium314.keyboard.latin.inputlogic;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.any;

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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.mockito.ArgumentCaptor;

@RunWith(RobolectricTestRunner.class)
public class SwipeQueuedWorkTest {
    @Test public void twoReleasedGesturesSurviveOneOccupiedSuggestionWorker() throws Exception {
        final LatinIME.UIHandler ui = mock(LatinIME.UIHandler.class);
        final InputLogic logic = mock(InputLogic.class);
        final Field composer = InputLogic.class.getDeclaredField("mWordComposer");
        composer.setAccessible(true);
        composer.set(logic, new WordComposer());
        doAnswer(call -> {
            final int sequence = call.getArgument(1);
            final WordComposer snapshot = call.getArgument(2);
            final Suggest.OnGetSuggestedWordsCallback callback = call.getArgument(3);
            final int firstX = snapshot.getInputPointers().getXCoordinates()[0];
            final String word = firstX == 10 ? "alpha" : firstX == 20 ? "beta" : "wrong-points";
            callback.onGetSuggestedWords(new SuggestedWords(
                    new ArrayList<>(List.of(new SuggestedWords.SuggestedWordInfo(
                            word, "", 100, SuggestedWords.SuggestedWordInfo.KIND_CORRECTION,
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
        try {
            handler.getSuggestedWords(() -> {
                workerEntered.countDown();
                try {
                    releaseWorker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            });
            assertTrue("prediction work did not occupy the queue", workerEntered.await(2, TimeUnit.SECONDS));
            final InputPointers a = new InputPointers(1);
            a.addPointer(10, 20, 0, 0);
            final InputPointers b = new InputPointers(1);
            b.addPointer(20, 20, 0, 0);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(a, 1);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(b, 2);
            releaseWorker.countDown();
            handler.getSuggestedWords(workerFinished::countDown);
            assertTrue("queued gestures did not finish", workerFinished.await(2, TimeUnit.SECONDS));
            final ArgumentCaptor<SuggestedWords> tails = ArgumentCaptor.forClass(SuggestedWords.class);
            verify(ui, times(2)).showTailBatchInputResult(tails.capture());
            assertEquals("alpha", tails.getAllValues().get(0).getWord(0));
            assertEquals("beta", tails.getAllValues().get(1).getWord(0));
        } finally {
            releaseWorker.countDown();
            handler.mNonUIThreadHandler.getLooper().quitSafely();
        }
    }

    @Test public void editorResetCancelsTailEvenWhenRecognitionIsRunning() throws Exception {
        final LatinIME.UIHandler ui = mock(LatinIME.UIHandler.class);
        final InputLogic logic = mock(InputLogic.class);
        final Field composer = InputLogic.class.getDeclaredField("mWordComposer");
        composer.setAccessible(true);
        composer.set(logic, new WordComposer());
        final CountDownLatch queryEntered = new CountDownLatch(1);
        final CountDownLatch releaseQuery = new CountDownLatch(1);
        final CountDownLatch workerFinished = new CountDownLatch(1);
        doAnswer(call -> {
            queryEntered.countDown();
            assertTrue("query stayed blocked", releaseQuery.await(2, TimeUnit.SECONDS));
            final Suggest.OnGetSuggestedWordsCallback callback = call.getArgument(3);
            callback.onGetSuggestedWords(SuggestedWords.getEmptyInstance());
            return null;
        }).when(logic).getSuggestedWords(anyInt(), anyInt(), any(WordComposer.class), any());

        final InputLogicHandler handler = new InputLogicHandler(ui, logic);
        try {
            final InputPointers a = new InputPointers(1);
            a.addPointer(10, 20, 0, 0);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(a, 1);
            assertTrue("query did not start", queryEntered.await(2, TimeUnit.SECONDS));
            handler.reset(); // InputLogic.startInput does this for a new editor.
            releaseQuery.countDown();
            handler.getSuggestedWords(workerFinished::countDown);
            assertTrue("worker did not finish", workerFinished.await(2, TimeUnit.SECONDS));
            verify(ui, never()).showTailBatchInputResult(any());
        } finally {
            releaseQuery.countDown();
            handler.mNonUIThreadHandler.getLooper().quitSafely();
        }
    }

    @Test public void cancelAfterReleaseRetiresOnlyCurrentQueuedTail() throws Exception {
        final LatinIME.UIHandler ui = mock(LatinIME.UIHandler.class);
        final InputLogic logic = mock(InputLogic.class);
        final Field composer = InputLogic.class.getDeclaredField("mWordComposer");
        composer.setAccessible(true);
        composer.set(logic, new WordComposer());
        final CountDownLatch workerEntered = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        doAnswer(call -> {
            final int sequence = call.getArgument(1);
            final Suggest.OnGetSuggestedWordsCallback callback = call.getArgument(3);
            callback.onGetSuggestedWords(new SuggestedWords(
                    new ArrayList<>(List.of(new SuggestedWords.SuggestedWordInfo(
                            sequence == 1 ? "alpha" : "beta", "", 100,
                            SuggestedWords.SuggestedWordInfo.KIND_CORRECTION,
                            Dictionary.DICTIONARY_APPLICATION_DEFINED,
                            SuggestedWords.SuggestedWordInfo.NOT_AN_INDEX,
                            SuggestedWords.SuggestedWordInfo.NOT_A_CONFIDENCE))),
                    null, null, true, false, false, SuggestedWords.INPUT_STYLE_TAIL_BATCH, sequence));
            return null;
        }).when(logic).getSuggestedWords(anyInt(), anyInt(), any(WordComposer.class), any());
        final InputLogicHandler handler = new InputLogicHandler(ui, logic);
        final CountDownLatch firstTail = new CountDownLatch(1);
        final CountDownLatch workerFinished = new CountDownLatch(1);
        doAnswer(call -> { firstTail.countDown(); return null; })
                .when(ui).showTailBatchInputResult(any());
        try {
            handler.getSuggestedWords(() -> {
                workerEntered.countDown();
                try { releaseWorker.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException error) { Thread.currentThread().interrupt(); }
            });
            assertTrue(workerEntered.await(2, TimeUnit.SECONDS));
            final InputPointers points = new InputPointers(1);
            points.addPointer(10, 20, 0, 0);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(points, 1);
            handler.onStartBatchInput();
            handler.updateTailBatchInput(points, 2);
            assertTrue("released B must be cancelable", handler.onCancelBatchInput());
            assertFalse("cancel must not repeat", handler.onCancelBatchInput());
            releaseWorker.countDown();
            assertTrue("earlier A must still finish", firstTail.await(2, TimeUnit.SECONDS));
            handler.getSuggestedWords(workerFinished::countDown);
            assertTrue(workerFinished.await(2, TimeUnit.SECONDS));
            verify(ui, times(1)).showTailBatchInputResult(any());
        } finally {
            releaseWorker.countDown();
            handler.mNonUIThreadHandler.getLooper().quitSafely();
        }
    }
}
