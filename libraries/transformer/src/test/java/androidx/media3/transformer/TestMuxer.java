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
package androidx.media3.transformer;

import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.Metadata;
import androidx.media3.test.utils.DumpableFormat;
import androidx.media3.test.utils.Dumper;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * An implementation of {@link Muxer} that supports dumping information about all interactions (for
 * testing purposes) and delegates the actual muxing operations to another {@link Muxer} created
 * using the factory provided.
 */
public final class TestMuxer implements Muxer, Dumper.Dumpable {

  private final Muxer muxer;
  private final List<Dumper.Dumpable> dumpables;

  /** Creates a new test muxer. */
  public TestMuxer(String path, Muxer.Factory muxerFactory) throws MuxerException {
    muxer = muxerFactory.create(path);
    dumpables = new ArrayList<>();
  }

  // Muxer implementation.

  @Override
  public int addTrack(Format format) throws MuxerException {
    int trackIndex = muxer.addTrack(format);
    dumpables.add(new DumpableFormat(format, trackIndex));
    return trackIndex;
  }

  @Override
  public void writeSampleData(
      int trackIndex, ByteBuffer data, long presentationTimeUs, @C.BufferFlags int flags)
      throws MuxerException {
    dumpables.add(
        new DumpableSample(
            trackIndex,
            data,
            (flags & C.BUFFER_FLAG_KEY_FRAME) == C.BUFFER_FLAG_KEY_FRAME,
            presentationTimeUs));
    muxer.writeSampleData(trackIndex, data, presentationTimeUs, flags);
  }

  @Override
  public void addMetadata(Metadata metadata) {
    dumpables.add(dumper -> dumper.add("metadata", metadata));
    muxer.addMetadata(metadata);
  }

  @Override
  public void release(boolean forCancellation) throws MuxerException {
    dumpables.add(dumper -> dumper.add("released", true));
    muxer.release(forCancellation);
  }

  @Override
  public long getMaxDelayBetweenSamplesMs() {
    return muxer.getMaxDelayBetweenSamplesMs();
  }

  // Dumper.Dumpable implementation.

  @Override
  public void dump(Dumper dumper) {
    for (Dumper.Dumpable dumpable : dumpables) {
      dumpable.dump(dumper);
    }
  }

  private static final class DumpableSample implements Dumper.Dumpable {

    private final int trackIndex;
    private final long presentationTimeUs;
    private final boolean isKeyFrame;
    private final int sampleDataHashCode;
    private final int sampleSize;

    public DumpableSample(
        int trackIndex, ByteBuffer sample, boolean isKeyFrame, long presentationTimeUs) {
      this.trackIndex = trackIndex;
      this.presentationTimeUs = presentationTimeUs;
      this.isKeyFrame = isKeyFrame;
      int initialPosition = sample.position();
      sampleSize = sample.remaining();
      byte[] data = new byte[sampleSize];
      sample.get(data);
      sample.position(initialPosition);
      sampleDataHashCode = Arrays.hashCode(data);
    }

    @Override
    public void dump(Dumper dumper) {
      dumper
          .startBlock("sample")
          .add("trackIndex", trackIndex)
          .add("dataHashCode", sampleDataHashCode)
          .add("size", sampleSize)
          .add("isKeyFrame", isKeyFrame)
          .add("presentationTimeUs", presentationTimeUs)
          .endBlock();
    }
  }
}
