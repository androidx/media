package androidx.media3.exoplayer;

import android.os.Looper;
import androidx.annotation.RequiresApi;
import java.util.concurrent.Phaser;

/**
 * Used to react to the corresponding ExoPlayer instance requesting private work on its dedicated
 * looper's thread.
 */
public interface EventHandlerRequestCallback {

  /**
   * Invoked at any time after a {@link Runnable} has been posted to the corresponding ExoPlayer's
   * instance dedicated looper's thread. This callback will be invoked on a specifically designated
   * thread where ExoPlayer will request nothing else to run on, immediately as the triggering
   * {@link android.os.Handler#post(Runnable)} call has finished. You may use the given
   * {@link Phaser} to track of whether all posted Runnables have finished execution or not.
   *
   * @param remainingPhaser A signal for how many posted Runnables have still not finished
   *                        execution. It is safe to block execution of the thread associated to
   *                        ExoPlayer's while and only while there are no yet-to-arrive parties on
   *                        this object. Blocking the thread associated to ExoPlayer's looper
   *                        otherwise may result on incorrect behavior, deadlocks, or exceptions.
   * @see ExoPlayer.Builder#setLooper(Looper, EventHandlerRequestCallback)
   */
  void onRunnablePosted(Phaser remainingPhaser);
}
