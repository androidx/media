/*
 * Copyright (C) 2016 The Android Open Source Project
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
package androidx.media3.test.utils;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;
import static java.lang.Math.max;
import static java.lang.Math.min;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceException;
import androidx.media3.datasource.DataSpec;
import androidx.media3.test.utils.FakeDataSet.FakeData;
import androidx.media3.test.utils.FakeDataSet.FakeData.Segment;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.util.ArrayList;

/**
 * A fake {@link DataSource} capable of simulating various scenarios. It uses a {@link FakeDataSet}
 * instance which determines the response to data access calls.
 */
@UnstableApi
public class FakeDataSource extends BaseDataSource {

  /** Factory to create a {@link FakeDataSource}. */
  public static class Factory implements DataSource.Factory {

    protected FakeDataSet fakeDataSet;
    protected boolean isNetwork;

    public Factory() {
      fakeDataSet = new FakeDataSet();
    }

    @CanIgnoreReturnValue
    public final Factory setFakeDataSet(FakeDataSet fakeDataSet) {
      this.fakeDataSet = fakeDataSet;
      return this;
    }

    @CanIgnoreReturnValue
    public final Factory setIsNetwork(boolean isNetwork) {
      this.isNetwork = isNetwork;
      return this;
    }

    @Override
    public FakeDataSource createDataSource() {
      return new FakeDataSource(fakeDataSet, isNetwork);
    }
  }

  private final FakeDataSet fakeDataSet;
  private final ArrayList<DataSpec> openedDataSpecs;

  @Nullable private Uri uri;
  private boolean openCalled;
  private boolean sourceOpened;
  @Nullable private FakeData fakeData;
  private int currentSegmentIndex;
  private long bytesRemaining;

  public FakeDataSource() {
    this(new FakeDataSet());
  }

  public FakeDataSource(FakeDataSet fakeDataSet) {
    this(fakeDataSet, /* isNetwork= */ false);
  }

  public FakeDataSource(FakeDataSet fakeDataSet, boolean isNetwork) {
    super(isNetwork);
    checkNotNull(fakeDataSet);
    this.fakeDataSet = fakeDataSet;
    this.openedDataSpecs = new ArrayList<>();
  }

  public final FakeDataSet getDataSet() {
    return fakeDataSet;
  }

  @Override
  public final long open(DataSpec dataSpec) throws IOException {
    checkState(!openCalled);
    openCalled = true;

    // DataSpec requires a matching close call even if open fails.
    uri = dataSpec.uri;
    openedDataSpecs.add(dataSpec);

    transferInitializing(dataSpec);
    FakeData fakeData = fakeDataSet.getData(dataSpec.uri.toString());
    if (fakeData == null) {
      throw new IOException("Data not found: " + dataSpec.uri);
    }
    this.fakeData = fakeData;

    long totalLength = 0;
    for (Segment segment : fakeData.getSegments()) {
      totalLength += segment.length;
    }

    if (totalLength == 0) {
      throw new IOException("Data is empty: " + dataSpec.uri);
    }

    if (dataSpec.position > totalLength) {
      throw new DataSourceException(PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE);
    }

    // Scan through the segments, configuring them for the current read.
    boolean findingCurrentSegmentIndex = true;
    currentSegmentIndex = 0;
    int scannedLength = 0;
    for (Segment segment : fakeData.getSegments()) {
      segment.bytesRead = (int) min(max(0, dataSpec.position - scannedLength), segment.length);
      scannedLength += segment.length;
      findingCurrentSegmentIndex &=
          segment.isErrorSegment()
              ? segment.exceptionCleared
              : (!segment.isActionSegment() && segment.bytesRead == segment.length);
      if (findingCurrentSegmentIndex) {
        currentSegmentIndex++;
      }
    }
    sourceOpened = true;
    transferStarted(dataSpec);
    // Configure bytesRemaining, and return.
    if (dataSpec.length == C.LENGTH_UNSET) {
      bytesRemaining = totalLength - dataSpec.position;
      return fakeData.isSimulatingUnknownLength() ? C.LENGTH_UNSET : bytesRemaining;
    } else {
      bytesRemaining = dataSpec.length;
      return bytesRemaining;
    }
  }

  @Override
  public final int read(byte[] buffer, int offset, int length) throws IOException {
    checkState(sourceOpened);
    while (true) {
      FakeData fakeData = Util.castNonNull(this.fakeData);
      if (currentSegmentIndex == fakeData.getSegments().size() || bytesRemaining == 0) {
        return C.RESULT_END_OF_INPUT;
      }
      Segment current = fakeData.getSegments().get(currentSegmentIndex);
      if (current.isErrorSegment()) {
        if (!current.exceptionCleared) {
          current.exceptionThrown = true;
          throw (IOException) Util.castNonNull(current.exception).fillInStackTrace();
        } else {
          currentSegmentIndex++;
        }
      } else if (current.isActionSegment()) {
        currentSegmentIndex++;
        Util.castNonNull(current.action).run();
      } else {
        // Read at most bytesRemaining.
        length = (int) min(length, bytesRemaining);
        // Do not allow crossing of the segment boundary.
        length = min(length, current.length - current.bytesRead);
        // Perform the read and return.
        checkArgument(buffer.length - offset >= length);
        if (current.data != null) {
          System.arraycopy(current.data, current.bytesRead, buffer, offset, length);
        }
        onDataRead(length);
        bytesTransferred(length);
        bytesRemaining -= length;
        current.bytesRead += length;
        if (current.bytesRead == current.length) {
          currentSegmentIndex++;
        }
        return length;
      }
    }
  }

  @Override
  @Nullable
  public final Uri getUri() {
    return uri;
  }

  @Override
  public final void close() {
    checkState(openCalled);
    openCalled = false;
    uri = null;
    if (fakeData != null && currentSegmentIndex < fakeData.getSegments().size()) {
      Segment current = fakeData.getSegments().get(currentSegmentIndex);
      if (current.isErrorSegment() && current.exceptionThrown) {
        current.exceptionCleared = true;
      }
    }
    if (sourceOpened) {
      sourceOpened = false;
      transferEnded();
    }
    fakeData = null;
    onClosed();
  }

  /**
   * Returns the {@link DataSpec} instances passed to {@link #open(DataSpec)} since the last call to
   * this method.
   */
  public final DataSpec[] getAndClearOpenedDataSpecs() {
    DataSpec[] dataSpecs = openedDataSpecs.toArray(new DataSpec[0]);
    openedDataSpecs.clear();
    return dataSpecs;
  }

  /** Returns whether the data source is currently opened. */
  public final boolean isOpened() {
    return sourceOpened;
  }

  /** Called when data is being read. */
  protected void onDataRead(int bytesRead) throws IOException {
    // Do nothing. Can be overridden.
  }

  /** Called when the source is closed. */
  protected void onClosed() {
    // Do nothing. Can be overridden.
  }
}
