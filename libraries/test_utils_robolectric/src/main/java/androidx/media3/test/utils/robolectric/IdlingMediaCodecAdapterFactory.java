/*
 * Copyright 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.test.utils.robolectric;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.HandlerThread;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.HandlerWrapper;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.mediacodec.DefaultMediaCodecAdapterFactory;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter;
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter.Configuration;
import com.google.common.base.Supplier;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.util.Set;
import org.robolectric.shadows.ShadowLooper;

/**
 * A {@link MediaCodecAdapter.Factory} which can {@linkplain ShadowLooper#idle() idle} all the async
 * queueing and callback threads used in async MediaCodec mode.
 *
 * <p>This helps ensure that buffers are reliably queued and dequeued from MediaCodec as the
 * playback thread advances in tests. Without this, the queueing and callback threads run freely and
 * often make little progress before the test completes, leading to flakiness and unpredictable
 * results.
 */
@UnstableApi
public final class IdlingMediaCodecAdapterFactory implements MediaCodecAdapter.Factory {

  private final ThreadSupplier queueingThreadSupplier;
  private final ThreadSupplier callbackThreadSupplier;
  private final MediaCodecAdapter.Factory delegate;

  /**
   * Constructs an instance with no automatic idling. The async threads can be manually idled with
   * {@link #idleQueueingAndCallbackThreads()}.
   *
   * @param context A {@link Context}.
   */
  public IdlingMediaCodecAdapterFactory(Context context) {
    this(context, (HandlerWrapper) null);
  }

  /**
   * Constructs an instance which automatically idles the async threads using {@code clock}.
   *
   * <p>The async threads can also be manually idled with {@link #idleQueueingAndCallbackThreads()}.
   *
   * @param context A {@link Context}.
   * @param clock A clock to schedule the idling operations with.
   */
  public IdlingMediaCodecAdapterFactory(Context context, Clock clock) {
    this(context, clock.createHandler(checkNotNull(Looper.myLooper()), /* callback= */ null));
  }

  private IdlingMediaCodecAdapterFactory(Context context, @Nullable HandlerWrapper handler) {
    queueingThreadSupplier = new ThreadSupplier("IdlingMediaCodecCallback");
    callbackThreadSupplier = new ThreadSupplier("IdlingMediaCodecQueueing");
    delegate =
        new DefaultMediaCodecAdapterFactory(
            context, callbackThreadSupplier, queueingThreadSupplier);
    if (handler != null) {
      Runnable idlingRunnable =
          new Runnable() {
            @Override
            public void run() {
              idleQueueingAndCallbackThreads();
              if (handler.getLooper().getThread().isAlive()) {
                handler.postDelayed(this, /* delayMs= */ 5);
              }
            }
          };
      handler.postDelayed(idlingRunnable, /* delayMs= */ 5);
    }
  }

  @Override
  public MediaCodecAdapter createAdapter(Configuration configuration) throws IOException {
    return delegate.createAdapter(configuration);
  }

  /**
   * Calls {@link ShadowLooper#idle()} on all created queueing and callback threads with a non-null
   * {@linkplain HandlerThread#getLooper() looper}.
   */
  public void idleQueueingAndCallbackThreads() {
    idleThreads(queueingThreadSupplier.createdThreads);
    idleThreads(callbackThreadSupplier.createdThreads);
  }

  private static void idleThreads(Set<HandlerThread> threads) {
    for (HandlerThread thread : threads) {
      @Nullable Looper looper = thread.getLooper();
      if (looper != null && thread.isAlive()) {
        ShadowLooper shadowLooper = shadowOf(looper);
        try {
          shadowLooper.idle();
        } catch (IllegalStateException e) {
          // Ignorable, may happen if Looper is already quitting.
        }
      }
    }
  }

  private static final class ThreadSupplier implements Supplier<HandlerThread> {

    private final String label;
    public final Set<HandlerThread> createdThreads;

    private int counter;

    private ThreadSupplier(String label) {
      this.label = label;
      this.createdThreads = Sets.newConcurrentHashSet();
    }

    @Override
    public HandlerThread get() {
      HandlerThread thread = new HandlerThread(label + ":" + counter++);
      createdThreads.add(thread);
      return thread;
    }
  }
}
