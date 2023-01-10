package androidx.media3.exoplayer;

import static androidx.media3.common.util.Assertions.checkNotNull;

import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import java.util.concurrent.Phaser;

final class EventHandlerRunnableDispatcher {

  private final Handler eventHandler;
  @Nullable private final Handler callbackHandler;
  @Nullable private final EventHandlerRequestCallback eventHandlerRequestCallback;
  @Nullable private final Phaser remainingPhaser;

  EventHandlerRunnableDispatcher(Handler eventHandler) {
    this.eventHandler = eventHandler;
    this.callbackHandler = null;
    this.eventHandlerRequestCallback = null;
    remainingPhaser = null;
  }

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  EventHandlerRunnableDispatcher(
      Handler eventHandler,
      @NonNull Handler callbackHandler,
      @NonNull EventHandlerRequestCallback eventHandlerRequestCallback
  ) {
    this.eventHandler = eventHandler;
    this.callbackHandler = callbackHandler;
    this.eventHandlerRequestCallback = eventHandlerRequestCallback;
    remainingPhaser = new Phaser();
  }

  Looper getEventLooper() {
    return eventHandler.getLooper();
  }

  void submit(Runnable runnable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      checkNotNull(callbackHandler);
      checkNotNull(eventHandlerRequestCallback);
      checkNotNull(remainingPhaser);
      remainingPhaser.register();
      eventHandler.post(() -> {
        try {
          runnable.run();
        } finally {
          remainingPhaser.arriveAndDeregister();
        }
      });
      callbackHandler.post(() -> eventHandlerRequestCallback.onRunnablePosted(remainingPhaser));
    } else {
      eventHandler.post(runnable);
    }
  }
}
