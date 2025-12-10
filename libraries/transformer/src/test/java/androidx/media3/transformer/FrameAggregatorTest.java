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
package androidx.media3.transformer;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.GlTextureInfo;
import androidx.media3.effect.GlTextureFrame;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FrameAggregator}. */
@RunWith(AndroidJUnit4.class)
public class FrameAggregatorTest {

  private Set<GlTextureInfo> releasedTextures;
  private ArrayList<List<GlTextureFrame>> outputFrames;

  @Before
  public void setUp() {
    releasedTextures = new HashSet<>();
    outputFrames = new ArrayList<>();
  }

  @Test
  public void queueFrame_withInvalidSequenceIndex_throwsIllegalArgumentException() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame frame = createFrame(/* presentationTimeUs= */ 100);

    assertThrows(IllegalArgumentException.class, () -> frameAggregator.queueFrame(frame, 2));
  }

  @Test
  public void queueFrame_singleSequence_passesFramesThrough() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 1, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame frame1 = createFrame(/* presentationTimeUs= */ 100);
    GlTextureFrame frame2 = createFrame(/* presentationTimeUs= */ 200);

    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame2, /* sequenceIndex= */ 0);

    assertThat(outputFrames).hasSize(2);
    assertThat(outputFrames.get(0)).containsExactly(frame1);
    assertThat(outputFrames.get(1)).containsExactly(frame2);
  }

  @Test
  public void queueFrame_waitsForAllSequencesBeforeAggregating() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame primaryFrame = createFrame(/* presentationTimeUs= */ 100);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    // Should not output a frame yet because the secondary sequence is missing.
    assertThat(outputFrames).isEmpty();

    GlTextureFrame secondaryFrame = createFrame(/* presentationTimeUs= */ 100);
    frameAggregator.queueFrame(secondaryFrame, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame, secondaryFrame);
  }

  @Test
  public void queueFrame_dropsSecondaryFramesWithEarlierPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame secondaryFrame1 = createFrame(/* presentationTimeUs= */ 50);
    GlTextureFrame secondaryFrame2 = createFrame(/* presentationTimeUs= */ 80);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);

    GlTextureFrame primaryFrame = createFrame(/* presentationTimeUs= */ 100);
    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);

    // Frames before the primary frame should be dropped and released.
    assertThat(releasedTextures).contains(secondaryFrame1.glTextureInfo);
    assertThat(releasedTextures).contains(secondaryFrame2.glTextureInfo);
    assertThat(outputFrames).isEmpty();

    GlTextureFrame secondaryFrame3 = createFrame(/* presentationTimeUs= */ 110);
    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame, secondaryFrame3);
  }

  @Test
  public void queueFrame_selectsSecondaryFrameWithEqualPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame primaryFrame = createFrame(/* presentationTimeUs= */ 200);
    GlTextureFrame secondaryFrame1 = createFrame(/* presentationTimeUs= */ 199);
    GlTextureFrame secondaryFrame2 = createFrame(/* presentationTimeUs= */ 200);
    GlTextureFrame secondaryFrame3 = createFrame(/* presentationTimeUs= */ 201);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame, secondaryFrame2);
    assertThat(releasedTextures).contains(secondaryFrame1.glTextureInfo);
  }

  @Test
  public void queueFrame_selectsSecondaryFrameWithGreaterPresentationTimeUs() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame primaryFrame = createFrame(/* presentationTimeUs= */ 200);
    GlTextureFrame secondaryFrame1 = createFrame(/* presentationTimeUs= */ 199);
    GlTextureFrame secondaryFrame2 = createFrame(/* presentationTimeUs= */ 201);
    GlTextureFrame secondaryFrame3 = createFrame(/* presentationTimeUs= */ 202);

    frameAggregator.queueFrame(primaryFrame, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(secondaryFrame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame2, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(secondaryFrame3, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(primaryFrame, secondaryFrame2);
    assertThat(releasedTextures).contains(secondaryFrame1.glTextureInfo);
  }

  @Test
  public void releaseAllFrames_releasesAllHeldFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 3, /* downstreamConsumer= */ outputFrames::add);
    GlTextureFrame frame0 = createFrame(/* presentationTimeUs= */ 100);
    GlTextureFrame frame1 = createFrame(/* presentationTimeUs= */ 100);
    GlTextureFrame frame2 = createFrame(/* presentationTimeUs= */ 100);
    GlTextureFrame frame3 = createFrame(/* presentationTimeUs= */ 150);
    GlTextureFrame frame4 = createFrame(/* presentationTimeUs= */ 150);

    frameAggregator.queueFrame(frame0, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame1, /* sequenceIndex= */ 1);
    frameAggregator.queueFrame(frame2, /* sequenceIndex= */ 2);
    frameAggregator.queueFrame(frame3, /* sequenceIndex= */ 0);
    frameAggregator.queueFrame(frame4, /* sequenceIndex= */ 1);

    assertThat(outputFrames).hasSize(1);
    assertThat(outputFrames.get(0)).containsExactly(frame0, frame1, frame2);

    frameAggregator.releaseAllFrames();

    assertThat(releasedTextures).hasSize(2);
    assertThat(releasedTextures).contains(frame3.glTextureInfo);
    assertThat(releasedTextures).contains(frame4.glTextureInfo);
    assertThat(outputFrames).hasSize(1);
  }

  @Test
  public void queueFrame_primarySeekBackwards_flushesHeldFrames() {
    FrameAggregator frameAggregator =
        new FrameAggregator(/* numSequences= */ 2, /* downstreamConsumer= */ outputFrames::add);
    frameAggregator.queueFrame(createFrame(100), 0);
    frameAggregator.queueFrame(createFrame(100), 1);
    assertThat(outputFrames).hasSize(1);

    GlTextureFrame heldSecondaryFrame1 = createFrame(200);
    GlTextureFrame heldSecondaryFrame2 = createFrame(300);
    frameAggregator.queueFrame(heldSecondaryFrame1, 1);
    frameAggregator.queueFrame(heldSecondaryFrame2, 1);
    assertThat(releasedTextures).doesNotContain(heldSecondaryFrame1.glTextureInfo);
    assertThat(releasedTextures).doesNotContain(heldSecondaryFrame2.glTextureInfo);

    GlTextureFrame seekFrame = createFrame(50);
    frameAggregator.queueFrame(seekFrame, 0);

    assertThat(releasedTextures).hasSize(2);
    assertThat(releasedTextures).contains(heldSecondaryFrame1.glTextureInfo);
    assertThat(releasedTextures).contains(heldSecondaryFrame2.glTextureInfo);
    assertThat(outputFrames).hasSize(1);
  }

  /** Creates a {@link GlTextureFrame} for testing. */
  private GlTextureFrame createFrame(long presentationTimeUs) {
    GlTextureInfo glTextureInfo =
        new GlTextureInfo(
            /* texId= */ (int) presentationTimeUs,
            /* fboId= */ 1,
            /* rboId= */ 1,
            /* width= */ 100,
            /* height= */ 100);
    return new GlTextureFrame.Builder(
            glTextureInfo, directExecutor(), /* releaseTextureCallback= */ releasedTextures::add)
        .setPresentationTimeUs(presentationTimeUs)
        .build();
  }
}
