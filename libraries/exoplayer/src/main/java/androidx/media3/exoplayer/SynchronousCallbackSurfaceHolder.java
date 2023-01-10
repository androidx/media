package androidx.media3.exoplayer;

import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Build;
import android.os.Handler;
import android.view.Surface;
import android.view.SurfaceHolder;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.HashMap;
import java.util.Map;

final class SynchronousCallbackSurfaceHolder implements SurfaceHolder {
  private final EventHandlerRunnableDispatcher eventHandlerRunnableDispatcher;
  private final SurfaceHolder delegate;
  private final Map<Callback, SynchronousSurfaceHolderCallback> mappedCallbacks = new HashMap<>();

  SynchronousCallbackSurfaceHolder(
      EventHandlerRunnableDispatcher eventHandlerRunnableDispatcher, SurfaceHolder delegate
  ) {
    this.eventHandlerRunnableDispatcher = eventHandlerRunnableDispatcher;
    this.delegate = delegate;
  }

  @Override
  public void addCallback(@Nullable Callback callback) {
    if (callback == null) {
      return;
    }
    synchronized (mappedCallbacks) {
      SynchronousSurfaceHolderCallback mappedCallback = new SynchronousSurfaceHolderCallback(
          eventHandlerRunnableDispatcher, callback
      );
      mappedCallbacks.put(callback, mappedCallback);
      delegate.addCallback(mappedCallback);
    }
  }

  @Override
  public void removeCallback(@Nullable Callback callback) {
    if (callback == null) {
      return;
    }
    synchronized (mappedCallbacks) {
      if (!mappedCallbacks.containsKey(callback)) {
        return;
      }
      SynchronousSurfaceHolderCallback mappedCallback = mappedCallbacks.get(callback);
      delegate.removeCallback(mappedCallback);
      mappedCallbacks.remove(callback);
    }
  }

  @Override
  public boolean isCreating() {
    return delegate.isCreating();
  }

  @Override
  public void setType(int type) {
    delegate.setType(type);
  }

  @Override
  public void setFixedSize(int width, int height) {
    delegate.setFixedSize(width, height);
  }

  @Override
  public void setSizeFromLayout() {
    delegate.setSizeFromLayout();
  }

  @Override
  public void setFormat(int format) {
    delegate.setFormat(format);
  }

  @Override
  public void setKeepScreenOn(boolean screenOn) {
    delegate.setKeepScreenOn(screenOn);
  }

  @Override
  public Canvas lockCanvas() {
    return delegate.lockCanvas();
  }

  @Override
  public Canvas lockCanvas(Rect dirty) {
    return delegate.lockCanvas(dirty);
  }

  @Override
  public void unlockCanvasAndPost(Canvas canvas) {
    delegate.unlockCanvasAndPost(canvas);
  }

  @Override
  public Rect getSurfaceFrame() {
    return delegate.getSurfaceFrame();
  }

  @Override
  public Surface getSurface() {
    return delegate.getSurface();
  }

  @Override
  @RequiresApi(api = Build.VERSION_CODES.O)
  public Canvas lockHardwareCanvas() {
    return delegate.lockHardwareCanvas();
  }
}
