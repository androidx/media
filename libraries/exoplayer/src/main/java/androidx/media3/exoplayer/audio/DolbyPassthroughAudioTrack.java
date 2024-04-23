/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.exoplayer.audio;

import android.media.*;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.HandlerThread;
import android.os.Handler;
import android.os.Message;
import android.util.Log;

import androidx.annotation.RequiresApi;
import java.util.concurrent.Semaphore;

/**
 * This class extends to an {@link android.media.AudioTrack} and handles
 * asynchronous writes to underlying DirectTrack implementation on Fire TV
 * family of devices to support Dolby Pass through playback.
 * APIs needs to be called from single thread.
 */

public final class DolbyPassthroughAudioTrack extends AudioTrack {

  private final String TAG = DolbyPassthroughAudioTrack.class.getSimpleName();

  // handle thread related
  private HandlerThread trackHandlerThread = null;
  private static final String TRACK_HANDLER_THREAD_NAME = "dolbyTrackHandlerThread";
  private Handler trackHandler = null;
  private ConditionVariable trackHandlerGate = null;

  // handler messages
  private static final int MSG_WRITE_TO_TRACK = 1;
  private static final int MSG_PAUSE_TRACK = 2;
  private static final int MSG_PLAY_TRACK = 3;
  private static final int MSG_FLUSH_TRACK = 4;
  private static final int MSG_STOP_TRACK = 5;
  private static final int MSG_RELEASE_TRACK = 6;

  // required for handling buffers
  // we allocate fixed number of buffers and cycle through them
  private static final int BUFFER_COUNT = 2;
  // Counting Semaphore for tracking ping/pong buffers
  private Semaphore pendingWriteSem = null;
  private byte[][] audioBuffer = null;
  // Next free buffer to be used to copy incoming writes
  private int nextBufferIndex = 0;

  @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
  public DolbyPassthroughAudioTrack(AudioAttributes attributes, AudioFormat format, int bufferSizeInBytes,
      int mode, int sessionId) {
    super(attributes, format, bufferSizeInBytes, mode, sessionId);
    initialize();
  }

  public DolbyPassthroughAudioTrack(int streamType, int sampleRateInHz,
      int channelConfig, int audioFormat,
      int bufferSizeInBytes, int mode)
      throws IllegalArgumentException {
    this(streamType, sampleRateInHz, channelConfig, audioFormat,
        bufferSizeInBytes, mode, 0);
  }

  public DolbyPassthroughAudioTrack(int streamType, int sampleRateInHz,
      int channelConfig, int audioFormat,
      int bufferSizeInBytes, int mode, int sessionId)
      throws IllegalArgumentException {
    super(streamType, sampleRateInHz, channelConfig, audioFormat,
        bufferSizeInBytes, mode, sessionId);
    initialize();
  }

  private void initialize() {
    Log.i(TAG, "initialize");
    trackHandlerGate = new ConditionVariable(true);
    trackHandlerThread = new HandlerThread(TRACK_HANDLER_THREAD_NAME);
    pendingWriteSem = new Semaphore(BUFFER_COUNT);
    audioBuffer = new byte[BUFFER_COUNT][];

    trackHandlerThread.start();
    /**
     * This handler thread serializes all the base audio track APIs.
     */
    trackHandler = new Handler(trackHandlerThread.getLooper()) {
      public void handleMessage(Message msg) {
        switch (msg.what) {
          case MSG_WRITE_TO_TRACK: {
            int size = msg.arg1;
            int bufferIndex = msg.arg2;
            //Log.v("writing to track : size = " + size +
            //  " bufferIndex = " + bufferIndex);
            DolbyPassthroughAudioTrack.super.write( audioBuffer[ bufferIndex ], 0, size );
            //log.v("writing to  track  done");
            pendingWriteSem.release();
            break;
          }
          case MSG_PAUSE_TRACK : {
            Log.i(TAG, "pausing track");
            DolbyPassthroughAudioTrack.super.pause();
            trackHandlerGate.open();
            break;
          }
          case MSG_PLAY_TRACK : {
            Log.i(TAG, "playing track");
            DolbyPassthroughAudioTrack.super.play();
            trackHandlerGate.open();
            break;
          }
          case MSG_FLUSH_TRACK : {
            Log.i(TAG, "flushing track");
            DolbyPassthroughAudioTrack.super.flush();
            trackHandlerGate.open();
            break;
          }
          case MSG_STOP_TRACK : {
            Log.i(TAG, "stopping track");
            DolbyPassthroughAudioTrack.super.stop();
            trackHandlerGate.open();
            break;
          }
          case MSG_RELEASE_TRACK : {
            Log.i(TAG, "releasing track");
            if (DolbyPassthroughAudioTrack.super.getPlayState() != PLAYSTATE_STOPPED) {
              Log.i(TAG, "not in stopped state...stopping");
              DolbyPassthroughAudioTrack.super.stop();
            }
            DolbyPassthroughAudioTrack.super.release();
            trackHandlerGate.open();
            break;
          }
          default: {
            Log.w(TAG, "unknown message..ignoring!!!");
            break;
          }
        }
      }
    };
  }

  /**
   * Play will block until previous messages to handler Thread
   * are executed.
   * We need to serialize play, write, pause and release because otherwise,
   * base audio track  will misbehave.
   */
  @Override
  public void play()
      throws IllegalStateException {
    Log.i(TAG, "play");
    trackHandlerGate.close();
    Message msg = trackHandler.obtainMessage(MSG_PLAY_TRACK);
    //Log.d(TAG, "Sending play to DirectTrack handler thread");
    trackHandler.sendMessage(msg);
    trackHandlerGate.block();
    //Log.d(TAG, "DirectTrack Play done");
  }

  /**
   * Pause will block until previous (possibly write) messages to handler Thread
   * are executed.
   * We need to serialize play, write and pause because otherwise,
   * base audio track  will misbehave.
   */
  @Override
  public void pause()
      throws IllegalStateException {
    Log.i(TAG, "pause");
    trackHandlerGate.close();
    Message msg = trackHandler.obtainMessage(MSG_PAUSE_TRACK);
    //log.d("Sending pause directtrack handler thread");
    trackHandler.sendMessage(msg);
    trackHandlerGate.block();
    //log.d("Pausing Direct Track Done");
  }

  /**
   * FLush will block until previous (possibly write) messages to handler Thread
   * are executed.
   */
  @Override
  public void flush()
      throws IllegalStateException {
    Log.i(TAG, "flush");
    trackHandlerGate.close();
    Message msg = trackHandler.obtainMessage(MSG_FLUSH_TRACK);
    //log.d("Sending flush Directtrack handler thread");
    trackHandler.sendMessage(msg);
    trackHandlerGate.block();
    //log.d("Flushing Direct Track Done");
  }

  /**
   * Stop will block until previous (possibly write) messages to handler Thread
   * are executed.
   */
  @Override
  public void stop()
      throws IllegalStateException {
    Log.i(TAG, "stop");
    if (getPlayState() == PLAYSTATE_STOPPED) {
      Log.i(TAG, "already in stopped state");
      return;
    }
    trackHandlerGate.close();
    Message msg = trackHandler.obtainMessage(MSG_STOP_TRACK);
    //log.d("Sending stop Directtrack handler thread");
    trackHandler.sendMessage(msg);
    trackHandlerGate.block();
    //log.d("Stopping Direct Track Done");
  }

  /**
   * Queues up Write messages to the handler thread. The
   * writes happen only when the base audio track is in playing state.
   * We also use {@link DolbyPassthroughAudioTrack#BUFFER_COUNT} number
   * of buffers in a cyclic manner.
   */
  @Override
  public int write(byte[] audioData, int offsetInBytes, int sizeInBytes) {
    if (getPlayState() != android.media.AudioTrack.PLAYSTATE_PLAYING ) {
      Log.w(TAG, "not in play state..not writing buffer now...");
      return 0;
    }
    if (!pendingWriteSem.tryAcquire()) {
      //Log.v(TAG, "pending writes... not writing buffer now");
      return 0;
    }
    if (audioBuffer[nextBufferIndex] == null ||
        audioBuffer[nextBufferIndex].length < sizeInBytes) {
      //Log.v("Allocating buffer index = " + nextBufferIndex +
      //       "size = " + sizeInBytes);
      audioBuffer[nextBufferIndex] = new byte[sizeInBytes];
    }
    System.arraycopy(audioData,offsetInBytes,audioBuffer[nextBufferIndex],0,sizeInBytes);
    Message msg = trackHandler.obtainMessage(MSG_WRITE_TO_TRACK,
        sizeInBytes,
        nextBufferIndex);
    //log.v("Sending buffer to directtrack handler thread");
    trackHandler.sendMessage(msg);
    nextBufferIndex = ((nextBufferIndex + 1) % BUFFER_COUNT);

    return sizeInBytes;
  }

  /**
   * Release will block until previous messages to handler Thread
   * are executed.
   * We need to serialize play, write, pause and release because otherwise,
   * base audio track  will misbehave.
   */
  @Override
  public void release() {
    Log.i(TAG, "release");
    trackHandlerGate.close();
    Message msg = trackHandler.obtainMessage(MSG_RELEASE_TRACK);
    //log.d("Sending release directtrack handler thread");
    trackHandler.sendMessage(msg);
    trackHandlerGate.block();

    trackHandlerThread.quit();
    trackHandlerThread = null;
    trackHandler = null;
    trackHandlerGate = null;
    pendingWriteSem = null;
    audioBuffer = null;
    //log.d("Release track done");
  }
}