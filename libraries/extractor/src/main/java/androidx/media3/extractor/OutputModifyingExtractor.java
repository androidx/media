/*
 * Copyright 2024 The Android Open Source Project
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
package androidx.media3.extractor;

import android.net.Uri;
import android.util.SparseArray;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.extractor.text.SubtitleParser;
import com.google.common.base.Function;
import java.io.IOException;
import java.util.List;
import java.util.Map;

/** DO NOT SUBMIT document this and rename it */
public final class OutputModifyingExtractor implements Extractor {

  /**
   * A wrapping {@link androidx.media3.extractor.ExtractorsFactory} implementation that wraps each
   * returned {@link Extractor} instance with an {@link OutputModifyingExtractor}.
   */
  public static final class Factory implements ExtractorsFactory {

    private final ExtractorsFactory delegate;
    private final Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory;

    public Factory(
        ExtractorsFactory delegate, Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory) {
      this.delegate = delegate;
      this.wrappingTrackOutputFactory = wrappingTrackOutputFactory;
    }

    @Override
    public ExtractorsFactory setSubtitleParserFactory(
        SubtitleParser.Factory subtitleParserFactory) {
      return delegate.setSubtitleParserFactory(subtitleParserFactory);
    }

    @Override
    public Extractor[] createExtractors() {
      return wrapExtractors(delegate.createExtractors());
    }

    @Override
    public Extractor[] createExtractors(Uri uri, Map<String, List<String>> responseHeaders) {
      return wrapExtractors(delegate.createExtractors(uri, responseHeaders));
    }

    private Extractor[] wrapExtractors(Extractor[] extractors) {
      for (int i = 0; i < extractors.length; i++) {
        extractors[i] = new OutputModifyingExtractor(extractors[i], wrappingTrackOutputFactory);
      }
      return extractors;
    }
  }

  private final Extractor delegate;
  private final Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory;

  public OutputModifyingExtractor(
      Extractor delegate, Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory) {
    this.delegate = delegate;
    this.wrappingTrackOutputFactory = wrappingTrackOutputFactory;
  }

  @Override
  public boolean sniff(ExtractorInput input) throws IOException {
    return delegate.sniff(input);
  }

  @Override
  public void init(ExtractorOutput output) {
    delegate.init(new WrappingExtractorOutput(output, wrappingTrackOutputFactory));
  }

  @Override
  public @ReadResult int read(ExtractorInput input, PositionHolder seekPosition)
      throws IOException {
    return delegate.read(input, seekPosition);
  }

  @Override
  public void seek(long position, long timeUs) {
    delegate.seek(position, timeUs);
  }

  @Override
  public List<SniffFailure> getSniffFailureDetails() {
    return delegate.getSniffFailureDetails();
  }

  @Override
  public Extractor getUnderlyingImplementation() {
    return delegate.getUnderlyingImplementation();
  }

  @Override
  public void release() {
    delegate.release();
  }

  private static final class WrappingExtractorOutput implements ExtractorOutput {

    private final ExtractorOutput delegateExtractorOutput;
    private final Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory;
    private final SparseArray<TrackOutput> trackOutputs;

    private WrappingExtractorOutput(
        ExtractorOutput delegateExtractorOutput,
        Function<TrackOutput, TrackOutput> wrappingTrackOutputFactory) {
      this.delegateExtractorOutput = delegateExtractorOutput;
      this.wrappingTrackOutputFactory = wrappingTrackOutputFactory;
      trackOutputs = new SparseArray<>();
    }

    @Override
    public TrackOutput track(int id, @C.TrackType int type) {
      @Nullable TrackOutput trackOutput = trackOutputs.get(id);
      if (trackOutput == null) {
        trackOutput = wrappingTrackOutputFactory.apply(delegateExtractorOutput.track(id, type));
        trackOutputs.put(id, trackOutput);
      }
      return trackOutput;
    }

    @Override
    public void endTracks() {
      delegateExtractorOutput.endTracks();
    }

    @Override
    public void seekMap(SeekMap seekMap) {
      delegateExtractorOutput.seekMap(seekMap);
    }
  }
}
