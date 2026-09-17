/*
 * Copyright 2021 The Android Open Source Project
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
package androidx.media3.exoplayer.rtsp;

import static com.google.common.truth.Truth.assertThat;
import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.TrackGroup;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.TransferListener;
import androidx.media3.exoplayer.LoadingInfo;
import androidx.media3.exoplayer.source.MediaPeriod;
import androidx.media3.exoplayer.source.SampleStream;
import androidx.media3.exoplayer.trackselection.ExoTrackSelection;
import androidx.media3.exoplayer.trackselection.FixedTrackSelection;
import androidx.media3.exoplayer.upstream.DefaultAllocator;
import androidx.media3.test.utils.TestUtil;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.SocketFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests the {@link RtspMediaPeriod} using the {@link RtspServer}. */
@RunWith(AndroidJUnit4.class)
public final class RtspMediaPeriodTest {

  private static final long DEFAULT_TIMEOUT_MS = 8000;

  private final AtomicReference<TrackGroup> trackGroupAtomicReference = new AtomicReference<>();
  private final MediaPeriod.Callback mediaPeriodCallback =
      new MediaPeriod.Callback() {
        @Override
        public void onPrepared(MediaPeriod mediaPeriod) {
          trackGroupAtomicReference.set(mediaPeriod.getTrackGroups().get(0));
        }

        @Override
        public void onContinueLoadingRequested(MediaPeriod source) {
          source.continueLoading(new LoadingInfo.Builder().setPlaybackPositionUs(0).build());
        }
      };
  private RtpPacketStreamDump rtpPacketStreamDump;
  private RtspServer rtspServer;
  private RtspMediaPeriod mediaPeriod;

  @Before
  public void setUp() throws IOException {
    rtpPacketStreamDump = RtspTestUtils.readRtpPacketStreamDump("media/rtsp/aac-dump.json");
  }

  @After
  public void tearDown() {
    mediaPeriod.release();
    Util.closeQuietly(rtspServer);
  }

  @Test
  public void prepareMediaPeriod_refreshesSourceInfoAndCallsOnPrepared() throws Exception {
    AtomicLong refreshedSourceDurationMs = new AtomicLong();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> refreshedSourceDurationMs.set(timing.getDurationMs()),
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);

    assertThat(refreshedSourceDurationMs.get()).isEqualTo(50_460);
  }

  @Test
  public void prepareMediaPeriod_withWwwAuthentication_refreshesSourceInfoAndCallsOnPrepared()
      throws Exception {
    AtomicLong refreshedSourceDurationMs = new AtomicLong();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ true));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUriWithUserInfo(
                "username", "password", rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> refreshedSourceDurationMs.set(timing.getDurationMs()),
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);

    assertThat(refreshedSourceDurationMs.get()).isEqualTo(50_460);
  }

  @Test
  public void isLoading_afterInit_returnsFalse() throws Exception {
    rtspServer =
        new RtspServer(
            () ->
                new RtspResponse(
                    /* status= */ 200,
                    new RtspHeaders.Builder().add(RtspHeaders.PUBLIC, "OPTIONS").build()));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    assertThat(mediaPeriod.isLoading()).isFalse();
  }

  @Test
  public void isLoading_afterPrepare_returnsFalse() throws Exception {
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);

    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);

    assertThat(mediaPeriod.isLoading()).isFalse();
  }

  @Test
  public void isLoading_afterSelectTracksAndSendPlayRequest_returnsTrue() throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);

    SampleStream[] sampleStreams = new SampleStream[1];
    // Call selectTracks to initiate RTSP SETUP Request
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);

    assertThat(mediaPeriod.isLoading()).isTrue();
  }

  @Test
  public void seekToUs_withRapidScrubbingAfterSuccessfulPlayResponse_doesNotTriggerTcpFallback()
      throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new FakeRtpDataChannelFactory(),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    long seekPositionUs = 5000000;

    mediaPeriod.seekToUs(0);
    mediaPeriod.seekToUs(seekPositionUs);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(seekPositionUs);
  }

  @Test
  public void seekToUs_duringInitialization_succeedsWithoutException() throws Exception {
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                rtpPacketStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            new FakeRtpDataChannelFactory(),
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);

    // Perform multiple seeks before PLAY response is received (during RTSP_STATE_INIT).
    mediaPeriod.seekToUs(1000000);
    mediaPeriod.seekToUs(5000000);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(5000000);
  }

  @Test
  public void seekToUs_toEarlierPositionWhileSeekPending_buffersSamplesFromNewPosition()
      throws Exception {
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump =
        RtpPacketStreamDump.parse(
            TestUtil.getString(
                    ApplicationProvider.getApplicationContext(), "media/rtsp/aac-dump.json")
                .replace("profile-level-id=1", "profile-level-id=2"));
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ null,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);

    mediaPeriod.seekToUs(10_000_000);
    mediaPeriod.seekToUs(500_000);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(640_000);
  }

  @Test
  public void
      seekToUs_toEarlierPositionDuringPlayingStateWhilePausePending_buffersSamplesFromNewPosition()
          throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump =
        RtpPacketStreamDump.parse(
            TestUtil.getString(
                    ApplicationProvider.getApplicationContext(), "media/rtsp/aac-dump.json")
                .replace("profile-level-id=1", "profile-level-id=2"));
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);

    // Seek forward then backward while in RTSP_STATE_PLAYING waiting for PAUSE response.
    mediaPeriod.seekToUs(10_000_000);
    mediaPeriod.seekToUs(500_000);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(640_000);
  }

  @Test
  public void seekToUs_withinBufferedRange_retainsBufferedPosition() throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump =
        RtpPacketStreamDump.parse(
            TestUtil.getString(
                    ApplicationProvider.getApplicationContext(), "media/rtsp/aac-dump.json")
                .replace("profile-level-id=1", "profile-level-id=2"));
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);
    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(640_000);

    mediaPeriod.seekToUs(200_000);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(640_000);
  }

  @Test
  public void seekToUs_duringReadyStateWhilePlayResponseInFlight_requestsNewPlayAtLatestPosition()
      throws Exception {
    AtomicBoolean getPlayResponseReference = new AtomicBoolean();
    FakeRtpDataChannelFactory rtpDataChannelFactory = new FakeRtpDataChannelFactory();
    RtpPacketStreamDump aacLcStreamDump =
        RtpPacketStreamDump.parse(
            TestUtil.getString(
                    ApplicationProvider.getApplicationContext(), "media/rtsp/aac-dump.json")
                .replace("profile-level-id=1", "profile-level-id=2"));
    rtspServer =
        new RtspServer(
            new TestResponseProvider(
                aacLcStreamDump,
                /* getPlayResponseReference= */ getPlayResponseReference,
                /* isWwwAuthenticationMode= */ false));
    mediaPeriod =
        new RtspMediaPeriod(
            new DefaultAllocator(/* trimOnReset= */ true, C.DEFAULT_BUFFER_SEGMENT_SIZE),
            rtpDataChannelFactory,
            RtspTestUtils.getTestUri(rtspServer.startAndGetPortNumber()),
            /* listener= */ timing -> {},
            /* userAgent= */ "ExoPlayer:RtspPeriodTest",
            /* socketFactory= */ SocketFactory.getDefault(),
            /* debugLoggingEnabled= */ false);
    mediaPeriod.prepare(mediaPeriodCallback, /* positionUs= */ 0);
    RobolectricUtil.runMainLooperUntil(() -> trackGroupAtomicReference.get() != null);
    SampleStream[] sampleStreams = new SampleStream[1];
    mediaPeriod.selectTracks(
        new ExoTrackSelection[] {
          new FixedTrackSelection(trackGroupAtomicReference.get(), /* track= */ 0)
        },
        /* mayRetainStreamFlags= */ new boolean[] {false},
        sampleStreams,
        /* streamResetFlags= */ new boolean[] {true},
        /* positionUs= */ 10_000_000);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);

    // Seek to 500_000 us while in RTSP_STATE_READY before the client processes the first PLAY
    // response on the main looper.
    getPlayResponseReference.set(false);
    mediaPeriod.seekToUs(500_000);
    RobolectricUtil.runMainLooperUntil(getPlayResponseReference::get);
    RobolectricUtil.runMainLooperUntil(() -> mediaPeriod.getBufferedPositionUs() == 0);

    rtpDataChannelFactory.getDataChannel().enqueuePackets(aacLcStreamDump.packets);
    RobolectricUtil.runMainLooperUntil(
        rtpDataChannelFactory.getDataChannel()::areAllPacketsProcessed);

    assertThat(mediaPeriod.getBufferedPositionUs()).isEqualTo(640_000);
  }

  private static class TestResponseProvider implements RtspServer.ResponseProvider {
    private static final String SESSION_ID = "00000000";

    private final ImmutableList<RtpPacketStreamDump> rtpPacketStreamDumps;
    @Nullable private final AtomicBoolean getPlayResponseReference;
    private final boolean isWwwAuthenticationMode;

    private TestResponseProvider(
        RtpPacketStreamDump rtpPacketStreamDump,
        @Nullable AtomicBoolean getPlayResponseReference,
        boolean isWwwAuthenticationMode) {
      this.rtpPacketStreamDumps = ImmutableList.of(rtpPacketStreamDump);
      this.getPlayResponseReference = getPlayResponseReference;
      this.isWwwAuthenticationMode = isWwwAuthenticationMode;
    }

    @Override
    public RtspResponse getOptionsResponse() {
      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.PUBLIC, "OPTIONS, DESCRIBE, SETUP, PLAY, PAUSE")
              .build());
    }

    @Override
    public RtspResponse getDescribeResponse(Uri requestedUri, RtspHeaders headers) {
      if (isWwwAuthenticationMode) {
        String authorizationHeader = headers.get(RtspHeaders.AUTHORIZATION);
        if (authorizationHeader == null) {
          return new RtspResponse(
              /* status= */ 401,
              new RtspHeaders.Builder()
                  .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                  .add(
                      RtspHeaders.WWW_AUTHENTICATE,
                      "Digest realm=\"RTSP server\","
                          + " nonce=\"0cdfe9719e7373b7d5bb2913e2115f3f\","
                          + " opaque=\"5ccc069c403ebaf9f0171e9517f40e41\"")
                  .add(RtspHeaders.WWW_AUTHENTICATE, "BASIC realm=\"WallyWorld\"")
                  .build());
        }

        if (!authorizationHeader.contains("Digest")) {
          return new RtspResponse(
              401,
              new RtspHeaders.Builder()
                  .add(RtspHeaders.CSEQ, headers.get(RtspHeaders.CSEQ))
                  .build());
        }
      }

      return RtspTestUtils.newDescribeResponseWithSdpMessage(
          "v=0\r\n"
              + "o=- 1606776316530225 1 IN IP4 127.0.0.1\r\n"
              + "s=Exoplayer test\r\n"
              + "t=0 0\r\n"
              // The session is 50.46s long.
              + "a=range:npt=0-50.46\r\n",
          rtpPacketStreamDumps,
          requestedUri);
    }

    @Override
    public RtspResponse getSetupResponse(Uri requestedUri, RtspHeaders headers) {
      return new RtspResponse(
          /* status= */ 200, headers.buildUpon().add(RtspHeaders.SESSION, SESSION_ID).build());
    }

    @Override
    public RtspResponse getPlayResponse() {
      if (getPlayResponseReference != null) {
        getPlayResponseReference.set(true);
      }

      return new RtspResponse(
          /* status= */ 200,
          new RtspHeaders.Builder()
              .add(RtspHeaders.RTP_INFO, RtspTestUtils.getRtpInfoForDumps(rtpPacketStreamDumps))
              .build());
    }
  }

  private static final class FakeRtpDataChannel implements RtpDataChannel {
    private final LinkedBlockingQueue<byte[]> packetQueue = new LinkedBlockingQueue<>();
    private final AtomicBoolean allPacketsProcessed = new AtomicBoolean();

    private void enqueuePackets(List<String> hexPackets) {
      allPacketsProcessed.set(false);
      for (String hexPacket : hexPackets) {
        packetQueue.add(Util.getBytesFromHexString(hexPacket));
      }
      // Enqueue an empty sentinel packet so read() knows when all preceding packets have been
      // extracted and processed by the background loader thread.
      packetQueue.add(new byte[0]);
    }

    private boolean areAllPacketsProcessed() {
      return allPacketsProcessed.get();
    }

    @Override
    public String getTransport() {
      return "RTP/AVP;unicast;client_port=1234-1235";
    }

    @Override
    public int getLocalPort() {
      return 1234;
    }

    @Override
    public boolean needsClosingOnLoadCompletion() {
      return false;
    }

    @Nullable
    @Override
    public RtspMessageChannel.InterleavedBinaryDataListener getInterleavedBinaryDataListener() {
      return null;
    }

    @Override
    public void addTransferListener(TransferListener transferListener) {}

    @Override
    public long open(DataSpec dataSpec) {
      return C.LENGTH_UNSET;
    }

    @Nullable
    @Override
    public Uri getUri() {
      return null;
    }

    @Override
    public void close() {}

    @Override
    public int read(byte[] buffer, int offset, int length) {
      try {
        byte[] packet = packetQueue.take();
        if (packet.length == 0) {
          if (packetQueue.isEmpty()) {
            allPacketsProcessed.set(true);
          }
          // Block the read thread until a packet is available or interrupted, simulating a UDP
          // socket.
          packet = packetQueue.take();
        }
        int bytesToRead = min(length, packet.length);
        System.arraycopy(packet, /* srcPos= */ 0, buffer, offset, bytesToRead);
        return bytesToRead;
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return C.RESULT_END_OF_INPUT;
      }
    }
  }

  private static final class FakeRtpDataChannelFactory implements RtpDataChannel.Factory {
    private final FakeRtpDataChannel dataChannel = new FakeRtpDataChannel();

    @Override
    public RtpDataChannel createAndOpenDataChannel(int trackId) {
      return dataChannel;
    }

    private FakeRtpDataChannel getDataChannel() {
      return dataChannel;
    }

    @Override
    public RtpDataChannel.Factory createFallbackDataChannelFactory() {
      return new TransferRtpDataChannelFactory(DEFAULT_TIMEOUT_MS);
    }
  }
}
