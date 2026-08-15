/*
 * Copyright 2026 The Android Open Source Project
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
package androidx.media3.transformer;

import static androidx.media3.common.MimeTypes.isVideo;

import android.content.Context;
import android.net.Uri;
import androidx.media3.common.Format;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Metadata;
import androidx.media3.common.util.ConditionVariable;
import androidx.media3.muxer.BufferInfo;
import androidx.media3.muxer.Muxer;
import androidx.media3.muxer.MuxerException;
import androidx.media3.test.utils.TestTransformerBuilder;
import androidx.media3.test.utils.robolectric.RobolectricUtil;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import java.nio.ByteBuffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;

/** End-to-end test for {@link Transformer} with {@link InAppFragmentedMp4Muxer}. */
@RunWith(AndroidJUnit4.class)
public final class TransformerWithInAppFragmentedMp4MuxerEndToEndTest {
  private static final String MP4_FILE_PATH = "asset:///media/mp4/sample.mp4";

  @Rule public final TemporaryFolder temporaryFolder = new TemporaryFolder();

  private Context context;
  private String outputPath;

  @Before
  public void setUp() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    outputPath = temporaryFolder.newFile().getAbsolutePath();
  }

  @Test
  public void cancel_immediatelyAfterVideoTrackAdded_doesNotCrash() throws Exception {
    InAppFragmentedMp4Muxer.Factory inAppMuxerFactory = new InAppFragmentedMp4Muxer.Factory();
    inAppMuxerFactory.setVideoDurationUs(2_000_000L); // Triggers EOS write on close
    ConditionVariable videoTrackAddedCondition = new ConditionVariable();
    TestMuxerFactory testMuxerFactory =
        new TestMuxerFactory(inAppMuxerFactory, videoTrackAddedCondition);
    Transformer transformer =
        new TestTransformerBuilder(context).setMuxerFactory(testMuxerFactory).build();
    MediaItem mediaItem = MediaItem.fromUri(Uri.parse(MP4_FILE_PATH));

    transformer.start(mediaItem, outputPath);
    RobolectricUtil.runLooperUntil(
        transformer.getApplicationLooper(), videoTrackAddedCondition::isOpen);
    transformer.cancel();
  }

  private static final class TestMuxerFactory implements Muxer.Factory {
    private final Muxer.Factory nestedFactory;
    private final ConditionVariable videoTrackAddedCondition;

    TestMuxerFactory(Muxer.Factory nestedFactory, ConditionVariable videoTrackAddedCondition) {
      this.nestedFactory = nestedFactory;
      this.videoTrackAddedCondition = videoTrackAddedCondition;
    }

    @Override
    public Muxer create(String path) throws MuxerException {
      Muxer realMuxer = nestedFactory.create(path);
      return new Muxer() {
        @Override
        public int addTrack(Format format) throws MuxerException {
          int trackId = realMuxer.addTrack(format);
          if (isVideo(format.sampleMimeType)) {
            videoTrackAddedCondition.open();
          }
          return trackId;
        }

        @Override
        public void writeSampleData(int trackId, ByteBuffer byteBuffer, BufferInfo bufferInfo)
            throws MuxerException {
          realMuxer.writeSampleData(trackId, byteBuffer, bufferInfo);
        }

        @Override
        public void addMetadataEntry(Metadata.Entry metadataEntry) {
          realMuxer.addMetadataEntry(metadataEntry);
        }

        @Override
        public void close() throws MuxerException {
          realMuxer.close();
        }
      };
    }

    @Override
    public ImmutableList<String> getSupportedSampleMimeTypes(int trackType) {
      return nestedFactory.getSupportedSampleMimeTypes(trackType);
    }

    @Override
    public boolean supportsWritingNegativeTimestampsInEditList() {
      return nestedFactory.supportsWritingNegativeTimestampsInEditList();
    }
  }
}
