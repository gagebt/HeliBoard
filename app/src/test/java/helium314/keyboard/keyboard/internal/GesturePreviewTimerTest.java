package helium314.keyboard.keyboard.internal;

import android.os.Looper;
import helium314.keyboard.keyboard.PointerTracker;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.LooperMode;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class GesturePreviewTimerTest {
    @Test public void continuousMovesCannotPostponePreviewUntilRelease() {
        DrawingProxy owner = mock(DrawingProxy.class);
        PointerTracker tracker = mock(PointerTracker.class);
        TimerHandler timer = new TimerHandler(owner, 0, 100);
        try {
            // The observed replay sends move points every 8 ms for over a second.
            for (int i = 0; i < 125; i++) {
                timer.startUpdateBatchInputTimer(tracker);
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(8));
            }
            verify(tracker, times(10)).updateBatchInputByTimer(anyLong());
            timer.cancelAllUpdateBatchInputTimers();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
            verifyNoMoreInteractions(tracker);
        } finally {
            timer.cancelAllMessages();
        }
    }

    @Test public void releaseOrDisabledTimerDoesNotCreatePreviewWork() {
        DrawingProxy owner = mock(DrawingProxy.class);
        PointerTracker tracker = mock(PointerTracker.class);
        TimerHandler timer = new TimerHandler(owner, 0, 100);
        timer.startUpdateBatchInputTimer(tracker);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
        timer.cancelUpdateBatchInputTimer(tracker);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100));
        new TimerHandler(owner, 0, 0).startUpdateBatchInputTimer(tracker);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(200));
        verifyNoInteractions(tracker);
    }
}
