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
package androidx.media3.extractor.mp4;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.util.UnstableApi;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Encapsulates information describing an MP4 track. */
@UnstableApi
public final class Track {

  /** Builder for {@link Track}. */
  public static final class Builder {

    private int id;
    private @C.TrackType int type;
    private long timescale;
    private long movieTimescale;
    private long durationUs;
    private long mediaDurationUs;
    @Nullable private Format format;
    private @Transformation int sampleTransformation;
    @Nullable private TrackEncryptionBox[] sampleDescriptionEncryptionBoxes;
    private int nalUnitLengthFieldLength;
    @Nullable private long[] editListDurations;
    @Nullable private long[] editListMediaTimes;
    private boolean shouldBeExposed;
    private int chapterTrackId;

    public Builder() {
      type = C.TRACK_TYPE_UNKNOWN;
      timescale = TIMESCALE_UNSET;
      movieTimescale = TIMESCALE_UNSET;
      durationUs = C.TIME_UNSET;
      mediaDurationUs = C.TIME_UNSET;
      sampleTransformation = TRANSFORMATION_NONE;
      shouldBeExposed = true;
      chapterTrackId = C.INDEX_UNSET;
    }

    private Builder(Track track) {
      id = track.id;
      type = track.type;
      timescale = track.timescale;
      movieTimescale = track.movieTimescale;
      durationUs = track.durationUs;
      mediaDurationUs = track.mediaDurationUs;
      format = track.format;
      sampleTransformation = track.sampleTransformation;
      sampleDescriptionEncryptionBoxes = track.sampleDescriptionEncryptionBoxes;
      nalUnitLengthFieldLength = track.nalUnitLengthFieldLength;
      editListDurations = track.editListDurations;
      editListMediaTimes = track.editListMediaTimes;
      shouldBeExposed = track.shouldBeExposed;
      chapterTrackId = track.chapterTrackId;
    }

    /**
     * Sets {@link Track#id}. The default value is {@code 0}.
     *
     * @param id The {@link Track#id}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setId(int id) {
      this.id = id;
      return this;
    }

    /**
     * Sets {@link Track#type}. The default value is {@link C#TRACK_TYPE_UNKNOWN}.
     *
     * @param type The {@link Track#type}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setType(@C.TrackType int type) {
      this.type = type;
      return this;
    }

    /**
     * Sets {@link Track#timescale}. The default value is {@link #TIMESCALE_UNSET}.
     *
     * @param timescale The {@link Track#timescale}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setTimescale(long timescale) {
      this.timescale = timescale;
      return this;
    }

    /**
     * Sets {@link Track#movieTimescale}. The default value is {@link #TIMESCALE_UNSET}.
     *
     * @param movieTimescale The {@link Track#movieTimescale}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMovieTimescale(long movieTimescale) {
      this.movieTimescale = movieTimescale;
      return this;
    }

    /**
     * Sets {@link Track#durationUs}. The default value is {@link C#TIME_UNSET}.
     *
     * @param durationUs The {@link Track#durationUs}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setDurationUs(long durationUs) {
      this.durationUs = durationUs;
      return this;
    }

    /**
     * Sets {@link Track#mediaDurationUs}. The default value is {@link C#TIME_UNSET}.
     *
     * @param mediaDurationUs The {@link Track#mediaDurationUs}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setMediaDurationUs(long mediaDurationUs) {
      this.mediaDurationUs = mediaDurationUs;
      return this;
    }

    /**
     * Sets {@link Track#format}. This is a required value.
     *
     * @param format The {@link Track#format}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setFormat(Format format) {
      this.format = format;
      return this;
    }

    /**
     * Sets {@link Track#sampleTransformation}. The default value is {@link #TRANSFORMATION_NONE}.
     *
     * @param sampleTransformation The {@link Track#sampleTransformation}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSampleTransformation(@Transformation int sampleTransformation) {
      this.sampleTransformation = sampleTransformation;
      return this;
    }

    /**
     * Sets {@link Track#sampleDescriptionEncryptionBoxes}. The default value is {@code null}.
     *
     * @param sampleDescriptionEncryptionBoxes The {@link Track#sampleDescriptionEncryptionBoxes}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setSampleDescriptionEncryptionBoxes(
        @Nullable TrackEncryptionBox[] sampleDescriptionEncryptionBoxes) {
      this.sampleDescriptionEncryptionBoxes = sampleDescriptionEncryptionBoxes;
      return this;
    }

    /**
     * Sets {@link Track#nalUnitLengthFieldLength}. The default value is {@code 0}.
     *
     * @param nalUnitLengthFieldLength The {@link Track#nalUnitLengthFieldLength}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setNalUnitLengthFieldLength(int nalUnitLengthFieldLength) {
      this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
      return this;
    }

    /**
     * Sets {@link Track#editListDurations}. The default value is {@code null}.
     *
     * @param editListDurations The {@link Track#editListDurations}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEditListDurations(@Nullable long[] editListDurations) {
      this.editListDurations = editListDurations;
      return this;
    }

    /**
     * Sets {@link Track#editListMediaTimes}. The default value is {@code null}.
     *
     * @param editListMediaTimes The {@link Track#editListMediaTimes}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setEditListMediaTimes(@Nullable long[] editListMediaTimes) {
      this.editListMediaTimes = editListMediaTimes;
      return this;
    }

    /**
     * Sets whether the track should be exposed to the player. The default value is {@code true}.
     *
     * @param shouldBeExposed The {@link Track#shouldBeExposed}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setShouldBeExposed(boolean shouldBeExposed) {
      this.shouldBeExposed = shouldBeExposed;
      return this;
    }

    /**
     * Sets {@link Track#chapterTrackId}. The default value is {@link C#INDEX_UNSET}.
     *
     * @param chapterTrackId The {@link Track#chapterTrackId}.
     * @return This builder.
     */
    @CanIgnoreReturnValue
    public Builder setChapterTrackId(int chapterTrackId) {
      this.chapterTrackId = chapterTrackId;
      return this;
    }

    /**
     * Builds a new {@link Track} instance.
     *
     * @throws NullPointerException if {@link #setFormat} has not been called.
     */
    public Track build() {
      checkNotNull(format);
      return new Track(
          id,
          type,
          timescale,
          movieTimescale,
          durationUs,
          mediaDurationUs,
          format,
          sampleTransformation,
          sampleDescriptionEncryptionBoxes,
          nalUnitLengthFieldLength,
          editListDurations,
          editListMediaTimes,
          shouldBeExposed,
          chapterTrackId);
    }
  }

  /**
   * The transformation to apply to samples in the track, if any. One of {@link
   * #TRANSFORMATION_NONE} or {@link #TRANSFORMATION_CEA608_CDAT}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({TRANSFORMATION_NONE, TRANSFORMATION_CEA608_CDAT})
  public @interface Transformation {}

  /** A no-op sample transformation. */
  public static final int TRANSFORMATION_NONE = 0;

  /** A transformation for caption samples in cdat atoms. */
  public static final int TRANSFORMATION_CEA608_CDAT = 1;

  /** An unset or unknown timescale. */
  public static final long TIMESCALE_UNSET = -1;

  /** The track identifier. */
  public final int id;

  /**
   * One of {@link C#TRACK_TYPE_AUDIO}, {@link C#TRACK_TYPE_VIDEO} and {@link C#TRACK_TYPE_TEXT}.
   */
  public final @C.TrackType int type;

  /**
   * The track timescale, defined as the number of time units that pass in one second, or {@link
   * #TIMESCALE_UNSET} if unknown.
   */
  public final long timescale;

  /** The movie timescale, or {@link #TIMESCALE_UNSET} if unknown. */
  public final long movieTimescale;

  /** The duration of the track in microseconds, or {@link C#TIME_UNSET} if unknown. */
  public final long durationUs;

  /** The duration of the media in microseconds, or {@link C#TIME_UNSET} if unknown. */
  public final long mediaDurationUs;

  /** The format. */
  public final Format format;

  /**
   * One of {@code TRANSFORMATION_*}. Defines the transformation to apply before outputting each
   * sample.
   */
  public final @Transformation int sampleTransformation;

  /**
   * Durations of edit list segments in the {@linkplain #movieTimescale movie timescale}, or {@code
   * null} if there is no edit list.
   */
  @Nullable public final long[] editListDurations;

  /**
   * Media times for edit list segments in the {@linkplain #timescale track timescale}, or {@code
   * null} if there is no edit list.
   */
  @Nullable public final long[] editListMediaTimes;

  /**
   * The length in bytes of the NALUnitLength field in each sample. 0 for tracks that don't use
   * length-delimited NAL units.
   */
  public final int nalUnitLengthFieldLength;

  /** The identifier of the associated chapter track, or {@link C#INDEX_UNSET} if there is none. */
  public final int chapterTrackId;

  /** Whether the track should be exposed to the player. */
  public final boolean shouldBeExposed;

  @Nullable private final TrackEncryptionBox[] sampleDescriptionEncryptionBoxes;

  /**
   * @deprecated Use {@link Builder} instead.
   */
  // TODO: b/493608660 - Make this private once deprecation is removed.
  @Deprecated
  public Track(
      int id,
      @C.TrackType int type,
      long timescale,
      long movieTimescale,
      long durationUs,
      long mediaDurationUs,
      Format format,
      @Transformation int sampleTransformation,
      @Nullable TrackEncryptionBox[] sampleDescriptionEncryptionBoxes,
      int nalUnitLengthFieldLength,
      @Nullable long[] editListDurations,
      @Nullable long[] editListMediaTimes,
      boolean shouldBeExposed,
      int chapterTrackId) {
    this.id = id;
    this.type = type;
    this.timescale = timescale;
    this.movieTimescale = movieTimescale;
    this.durationUs = durationUs;
    this.mediaDurationUs = mediaDurationUs;
    this.format = format;
    this.sampleTransformation = sampleTransformation;
    this.sampleDescriptionEncryptionBoxes =
        sampleDescriptionEncryptionBoxes == null ? null : sampleDescriptionEncryptionBoxes.clone();
    this.nalUnitLengthFieldLength = nalUnitLengthFieldLength;
    this.editListDurations = editListDurations == null ? null : editListDurations.clone();
    this.editListMediaTimes = editListMediaTimes == null ? null : editListMediaTimes.clone();
    this.shouldBeExposed = shouldBeExposed;
    this.chapterTrackId = chapterTrackId;
  }

  /** Returns a new {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  /**
   * Returns the {@link TrackEncryptionBox} for the given sample description index.
   *
   * @param sampleDescriptionIndex The given sample description index
   * @return The {@link TrackEncryptionBox} for the given sample description index. Maybe null if no
   *     such entry exists.
   */
  @Nullable
  public TrackEncryptionBox getSampleDescriptionEncryptionBox(int sampleDescriptionIndex) {
    return sampleDescriptionEncryptionBoxes == null
        ? null
        : sampleDescriptionEncryptionBoxes[sampleDescriptionIndex];
  }

  /**
   * @deprecated Use {@link #buildUpon()} instead.
   */
  @Deprecated
  public Track copyWithFormat(Format format) {
    return buildUpon().setFormat(format).build();
  }

  /**
   * @deprecated Use {@link #buildUpon()} instead.
   */
  @Deprecated
  public Track copyWithoutEditLists() {
    return buildUpon().setEditListDurations(null).setEditListMediaTimes(null).build();
  }
}
