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
package androidx.media3.exoplayer.upstream;

import static com.google.common.base.Preconditions.checkNotNull;

import android.net.Uri;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.ParserException;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DataSourceInputStream;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.StatsDataSource;
import androidx.media3.exoplayer.source.LoadEventInfo;
import androidx.media3.exoplayer.upstream.Loader.Loadable;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/**
 * A {@link Loadable} for objects that can be parsed from binary data using a {@link Parser}.
 *
 * @param <T> The type of the object being loaded.
 */
@UnstableApi
public final class ParsingLoadable<T> implements Loadable {

  /** Parses an object from loaded data. */
  public interface Parser<T> {

    /**
     * Parses an object from a response.
     *
     * @param uri The source {@link Uri} of the response, after any redirection.
     * @param inputStream An {@link InputStream} from which the response data can be read.
     * @return The parsed object.
     * @throws ParserException If an error occurs parsing the data.
     * @throws IOException If an error occurs reading data from the stream.
     */
    T parse(Uri uri, InputStream inputStream) throws IOException;
  }

  /** Builder for {@link ParsingLoadable}. */
  public static final class Builder<T> {
    private final DataSource dataSource;
    private final DataSpec dataSpec;
    private final int type;
    private final Parser<? extends T> parser;
    @Nullable private String steeredPathwayId;

    /**
     * Creates a builder.
     *
     * @param dataSource A {@link DataSource} to use when loading the data.
     * @param uri The {@link Uri} from which the object should be loaded.
     * @param type See {@link ParsingLoadable#type}.
     * @param parser Parses the object from the response.
     */
    public Builder(DataSource dataSource, Uri uri, int type, Parser<? extends T> parser) {
      this(
          dataSource,
          new DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build(),
          type,
          parser);
    }

    /**
     * Creates a builder.
     *
     * @param dataSource A {@link DataSource} to use when loading the data.
     * @param dataSpec The {@link DataSpec} from which the object should be loaded.
     * @param type See {@link ParsingLoadable#type}.
     * @param parser Parses the object from the response.
     */
    public Builder(DataSource dataSource, DataSpec dataSpec, int type, Parser<? extends T> parser) {
      this.dataSource = dataSource;
      this.dataSpec = dataSpec;
      this.type = type;
      this.parser = parser;
    }

    /**
     * Sets the {@link ParsingLoadable#steeredPathwayId}, the default is {@code null}.
     *
     * @param steeredPathwayId See {@link ParsingLoadable#steeredPathwayId}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder<T> setSteeredPathwayId(@Nullable String steeredPathwayId) {
      this.steeredPathwayId = steeredPathwayId;
      return this;
    }

    /** Builds the {@link ParsingLoadable}. */
    public ParsingLoadable<T> build() {
      return new ParsingLoadable<>(this);
    }
  }

  /**
   * Loads a single parsable object.
   *
   * @param dataSource The {@link DataSource} through which the object should be read.
   * @param parser The {@link Parser} to parse the object from the response.
   * @param uri The {@link Uri} of the object to read.
   * @param type The type of the data. One of the {@link C}{@code DATA_TYPE_*} constants.
   * @return The parsed object
   * @throws IOException Thrown if there is an error while loading or parsing.
   */
  public static <T> T load(DataSource dataSource, Parser<? extends T> parser, Uri uri, int type)
      throws IOException {
    ParsingLoadable<T> loadable =
        new ParsingLoadable.Builder<T>(dataSource, uri, type, parser).build();
    loadable.load();
    return checkNotNull(loadable.getResult());
  }

  /**
   * Loads a single parsable object.
   *
   * @param dataSource The {@link DataSource} through which the object should be read.
   * @param parser The {@link Parser} to parse the object from the response.
   * @param dataSpec The {@link DataSpec} of the object to read.
   * @param type The type of the data. One of the {@link C}{@code DATA_TYPE_*} constants.
   * @return The parsed object
   * @throws IOException Thrown if there is an error while loading or parsing.
   */
  public static <T> T load(
      DataSource dataSource, Parser<? extends T> parser, DataSpec dataSpec, int type)
      throws IOException {
    ParsingLoadable<T> loadable =
        new ParsingLoadable.Builder<T>(dataSource, dataSpec, type, parser).build();
    loadable.load();
    return checkNotNull(loadable.getResult());
  }

  /** Identifies the load task for this loadable. */
  public final long loadTaskId;

  /** The {@link DataSpec} that defines the data to be loaded. */
  public final DataSpec dataSpec;

  /**
   * The type of the data. One of the {@code DATA_TYPE_*} constants defined in {@link C}. For
   * reporting only.
   */
  public final int type;

  /**
   * The ID of the steered pathway from which data is being loaded, or {@code null} if not
   * applicable.
   */
  @Nullable public final String steeredPathwayId;

  private final StatsDataSource dataSource;
  private final Parser<? extends T> parser;

  @Nullable private volatile T result;

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public ParsingLoadable(DataSource dataSource, Uri uri, int type, Parser<? extends T> parser) {
    this(
        dataSource,
        new DataSpec.Builder().setUri(uri).setFlags(DataSpec.FLAG_ALLOW_GZIP).build(),
        type,
        parser);
  }

  /**
   * @deprecated Use {@link Builder} instead.
   */
  @Deprecated
  public ParsingLoadable(
      DataSource dataSource, DataSpec dataSpec, int type, Parser<? extends T> parser) {
    this.dataSource = new StatsDataSource(dataSource);
    this.dataSpec = dataSpec;
    this.type = type;
    this.parser = parser;
    this.steeredPathwayId = null;
    loadTaskId = LoadEventInfo.getNewId();
  }

  private ParsingLoadable(Builder<T> builder) {
    this.dataSource = new StatsDataSource(builder.dataSource);
    this.dataSpec = builder.dataSpec;
    this.type = builder.type;
    this.parser = builder.parser;
    this.steeredPathwayId = builder.steeredPathwayId;
    loadTaskId = LoadEventInfo.getNewId();
  }

  /** Returns the loaded object, or null if an object has not been loaded. */
  @Nullable
  public final T getResult() {
    return result;
  }

  /**
   * Returns the number of bytes loaded. In the case that the network response was compressed, the
   * value returned is the size of the data <em>after</em> decompression. Must only be called after
   * the load completed, failed, or was canceled.
   */
  public long bytesLoaded() {
    return dataSource.getBytesRead();
  }

  /**
   * Returns the {@link Uri} from which data was read. If redirection occurred, this is the
   * redirected uri. Must only be called after the load completed, failed, or was canceled.
   */
  public Uri getUri() {
    return dataSource.getLastOpenedUri();
  }

  /**
   * Returns the response headers associated with the load. Must only be called after the load
   * completed, failed, or was canceled.
   */
  public Map<String, List<String>> getResponseHeaders() {
    return dataSource.getLastResponseHeaders();
  }

  @Override
  public final void cancelLoad() {
    // Do nothing.
  }

  @Override
  public final void load() throws IOException {
    // We always load from the beginning, so reset bytesRead to 0.
    dataSource.resetBytesRead();
    DataSourceInputStream inputStream = new DataSourceInputStream(dataSource, dataSpec);
    try {
      inputStream.open();
      Uri dataSourceUri = checkNotNull(dataSource.getUri());
      result = parser.parse(dataSourceUri, inputStream);
    } finally {
      Util.closeQuietly(inputStream);
    }
  }
}
