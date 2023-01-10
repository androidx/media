package androidx.media3.exoplayer;

import android.os.Looper;
import android.view.SurfaceHolder;
import androidx.annotation.NonNull;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

final class SynchronousSurfaceHolderCallback implements SurfaceHolder.Callback {
  private final EventHandlerRunnableDispatcher eventHandlerRunnableDispatcher;
  private final SurfaceHolder.Callback delegate;
  private final ReentrantLock reentrantLock = new ReentrantLock(true);

  SynchronousSurfaceHolderCallback(
      EventHandlerRunnableDispatcher eventHandlerRunnableDispatcher, SurfaceHolder.Callback delegate
  ) {
    this.eventHandlerRunnableDispatcher = eventHandlerRunnableDispatcher;
    this.delegate = delegate;
  }

  @Override
  public void surfaceCreated(@NonNull SurfaceHolder holder) {
    dispatchAndWaitFor(() -> delegate.surfaceCreated(holder));
  }

  @Override
  public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width, int height) {
    dispatchAndWaitFor(() -> delegate.surfaceChanged(holder, format, width, height));
  }

  @Override
  public void surfaceDestroyed(@NonNull SurfaceHolder holder) {
    dispatchAndWaitFor(() -> delegate.surfaceDestroyed(holder));
  }

  private void dispatchAndWaitFor(Runnable runnable) {
    if (eventHandlerRunnableDispatcher.getEventLooper() == Looper.myLooper()) {
      runnable.run();
      return;
    }
    AtomicBoolean finished = new AtomicBoolean(false);
    Condition condition = reentrantLock.newCondition();
    eventHandlerRunnableDispatcher.submit(() -> {
      runnable.run();
      reentrantLock.lock();
      try {
        condition.signal();
      } finally {
        reentrantLock.unlock();
      }
      finished.set(true);
    });
    reentrantLock.lock();
    try {
      while (!finished.get()) {
        condition.awaitUninterruptibly();
      }
    } finally {
      reentrantLock.unlock();
    }
  }
}
