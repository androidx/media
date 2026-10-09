/*
 * Copyright (C) 2026 The Android Open Source Project
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
package androidx.media3.exoplayer.dash.manifest;

import static com.google.common.base.Preconditions.checkNotNull;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.net.Uri;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import com.google.common.collect.ImmutableList;
import com.google.errorprone.annotations.CanIgnoreReturnValue;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.List;
import java.util.Objects;

/** An Alternative MPD Insertion or Replacement event, as defined by ISO/IEC 23009-1 clause 5.16. */
@UnstableApi
public final class AlternativeMpdEvent {

  /** A builder for {@link AlternativeMpdEvent} instances. */
  public static final class Builder {

    private final @Type int type;
    private long id;
    private boolean isUpdate;
    private String eventStreamValue;
    private long presentationTimeUs;
    private long durationUs;
    private Uri uri;
    private long earliestResolutionTimeOffsetUs;
    @Nullable private String serviceDescriptionId;
    private long maxDurationUs;
    private boolean executeOnce;
    private @NoJumpMode int noJump;
    private long skipAfterUs;
    private ImmutableList<Descriptor> supplementalProperties;
    private long returnOffsetUs;
    private boolean clip;
    private boolean startWithOffset;

    /**
     * Creates a builder with spec default values for optional fields.
     *
     * @param type The {@link Type} of the event.
     * @param id The {@code Event@id}.
     * @param uri The resolved URI of the alternative MPD.
     */
    public Builder(@Type int type, long id, Uri uri) {
      this.type = type;
      this.id = id;
      this.eventStreamValue = "";
      this.durationUs = C.TIME_UNSET;
      this.uri = checkNotNull(uri);
      this.earliestResolutionTimeOffsetUs = DEFAULT_EARLIEST_RESOLUTION_TIME_OFFSET_US;
      this.maxDurationUs = C.TIME_UNSET;
      this.noJump = NO_JUMP_NONE;
      this.supplementalProperties = ImmutableList.of();
      this.returnOffsetUs = C.TIME_UNSET;
      this.clip = true;
    }

    private Builder(AlternativeMpdEvent event) {
      this.type = event.type;
      this.id = event.id;
      this.isUpdate = event.isUpdate;
      this.eventStreamValue = event.eventStreamValue;
      this.presentationTimeUs = event.presentationTimeUs;
      this.durationUs = event.durationUs;
      this.uri = event.uri;
      this.earliestResolutionTimeOffsetUs = event.earliestResolutionTimeOffsetUs;
      this.serviceDescriptionId = event.serviceDescriptionId;
      this.maxDurationUs = event.maxDurationUs;
      this.executeOnce = event.executeOnce;
      this.noJump = event.noJump;
      this.skipAfterUs = event.skipAfterUs;
      this.supplementalProperties = event.supplementalProperties;
      this.returnOffsetUs = event.returnOffsetUs;
      this.clip = event.clip;
      this.startWithOffset = event.startWithOffset;
    }

    /** Sets the {@code Event@id}. */
    @CanIgnoreReturnValue
    public Builder setId(long id) {
      this.id = id;
      return this;
    }

    /**
     * Sets whether {@code Event@status} is {@code "update"}. Ignored for {@link #TYPE_INSERT}
     * events, where {@link #isUpdate} is always {@code false}.
     */
    @CanIgnoreReturnValue
    public Builder setIsUpdate(boolean isUpdate) {
      this.isUpdate = isUpdate;
      return this;
    }

    /** Sets the {@code EventStream@value}. Defaults to the empty string. */
    @CanIgnoreReturnValue
    public Builder setEventStreamValue(String eventStreamValue) {
      this.eventStreamValue = checkNotNull(eventStreamValue);
      return this;
    }

    /** Sets the presentation time (PRT) in microseconds, relative to the start of the period. */
    @CanIgnoreReturnValue
    public Builder setPresentationTimeUs(long presentationTimeUs) {
      this.presentationTimeUs = presentationTimeUs;
      return this;
    }

    /**
     * Sets the duration of the active interval in microseconds, or {@link C#TIME_UNSET} if active
     * until the end of the period. Defaults to {@link C#TIME_UNSET}.
     */
    @CanIgnoreReturnValue
    public Builder setDurationUs(long durationUs) {
      this.durationUs = durationUs;
      return this;
    }

    /** Sets the resolved URI of the alternative MPD. */
    @CanIgnoreReturnValue
    public Builder setUri(Uri uri) {
      this.uri = checkNotNull(uri);
      return this;
    }

    /**
     * Sets the earliest resolution time offset in microseconds before {@link #presentationTimeUs}.
     * Defaults to {@link #DEFAULT_EARLIEST_RESOLUTION_TIME_OFFSET_US}.
     */
    @CanIgnoreReturnValue
    public Builder setEarliestResolutionTimeOffsetUs(long earliestResolutionTimeOffsetUs) {
      this.earliestResolutionTimeOffsetUs = earliestResolutionTimeOffsetUs;
      return this;
    }

    /** Sets the {@code @serviceDescriptionId}, or {@code null} if absent. */
    @CanIgnoreReturnValue
    public Builder setServiceDescriptionId(@Nullable String serviceDescriptionId) {
      this.serviceDescriptionId = serviceDescriptionId;
      return this;
    }

    /**
     * Sets the maximum alternative presentation duration (APDmax) in microseconds, or {@link
     * C#TIME_UNSET} if unbounded. Defaults to {@link C#TIME_UNSET}.
     */
    @CanIgnoreReturnValue
    public Builder setMaxDurationUs(long maxDurationUs) {
      this.maxDurationUs = maxDurationUs;
      return this;
    }

    /** Sets {@code @executeOnce}. Defaults to {@code false}. */
    @CanIgnoreReturnValue
    public Builder setExecuteOnce(boolean executeOnce) {
      this.executeOnce = executeOnce;
      return this;
    }

    /** Sets the {@code @noJump} mode. Defaults to {@link #NO_JUMP_NONE}. */
    @CanIgnoreReturnValue
    public Builder setNoJump(@NoJumpMode int noJump) {
      this.noJump = noJump;
      return this;
    }

    /** Sets the {@code @skipAfter} offset in microseconds. Defaults to {@code 0}. */
    @CanIgnoreReturnValue
    public Builder setSkipAfterUs(long skipAfterUs) {
      this.skipAfterUs = skipAfterUs;
      return this;
    }

    /** Sets the {@code SupplementalProperty} descriptors. Defaults to an empty list. */
    @CanIgnoreReturnValue
    public Builder setSupplementalProperties(List<Descriptor> supplementalProperties) {
      this.supplementalProperties = ImmutableList.copyOf(supplementalProperties);
      return this;
    }

    /**
     * Sets the replacement {@code @returnOffset} in microseconds, or {@link C#TIME_UNSET} if
     * absent. Ignored for {@link #TYPE_INSERT} events, where {@link #returnOffsetUs} is always
     * {@link C#TIME_UNSET}.
     */
    @CanIgnoreReturnValue
    public Builder setReturnOffsetUs(long returnOffsetUs) {
      this.returnOffsetUs = returnOffsetUs;
      return this;
    }

    /**
     * Sets the replacement {@code @clip} value. Ignored for {@link #TYPE_INSERT} events, where
     * {@link #clip} is always {@code true}. Defaults to {@code true}.
     */
    @CanIgnoreReturnValue
    public Builder setClip(boolean clip) {
      this.clip = clip;
      return this;
    }

    /**
     * Sets the replacement {@code @startWithOffset} value. Ignored for {@link #TYPE_INSERT} events,
     * where {@link #startWithOffset} is always {@code false}. Defaults to {@code false}.
     */
    @CanIgnoreReturnValue
    public Builder setStartWithOffset(boolean startWithOffset) {
      this.startWithOffset = startWithOffset;
      return this;
    }

    /** Builds the {@link AlternativeMpdEvent}. */
    public AlternativeMpdEvent build() {
      return new AlternativeMpdEvent(this);
    }
  }

  /** The type of the event. One of {@link #TYPE_INSERT} or {@link #TYPE_REPLACE}. */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({TYPE_INSERT, TYPE_REPLACE})
  public @interface Type {}

  /** An Alternative MPD Insertion event ({@code InsertPresentation}). */
  public static final int TYPE_INSERT = 0;

  /** An Alternative MPD Replacement event ({@code ReplacePresentation}). */
  public static final int TYPE_REPLACE = 1;

  /**
   * The no-jump mode. One of {@link #NO_JUMP_NONE}, {@link #NO_JUMP_ALL}, {@link #NO_JUMP_LATEST}.
   */
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target(TYPE_USE)
  @IntDef({NO_JUMP_NONE, NO_JUMP_ALL, NO_JUMP_LATEST})
  public @interface NoJumpMode {}

  /** The active interval may be skipped by seeking ({@code @noJump="0"}). */
  public static final int NO_JUMP_NONE = 0;

  /** All skipped no-jump events are executed ({@code @noJump="1"}). */
  public static final int NO_JUMP_ALL = 1;

  /** Only the latest skipped no-jump event is executed ({@code @noJump="2"}). */
  public static final int NO_JUMP_LATEST = 2;

  /** The scheme URI of Alternative MPD Insertion events. */
  public static final String SCHEME_ID_URI_INSERT =
      "urn:mpeg:dash:event:alternativeMPD:insert:2025";

  /** The scheme URI of Alternative MPD Replacement events. */
  public static final String SCHEME_ID_URI_REPLACE =
      "urn:mpeg:dash:event:alternativeMPD:replace:2025";

  /** The default earliest resolution time offset in microseconds (60 seconds). */
  public static final long DEFAULT_EARLIEST_RESOLUTION_TIME_OFFSET_US = 60 * C.MICROS_PER_SECOND;

  /** The {@link Type} of the event. */
  public final @Type int type;

  /** The {@code Event@id}. */
  public final long id;

  /** Whether {@code Event@status} is {@code "update"}. Always false for insertion events. */
  public final boolean isUpdate;

  /** The {@code EventStream@value}, or the empty string if absent. */
  public final String eventStreamValue;

  /** PRT in microseconds, relative to the start of the period. */
  public final long presentationTimeUs;

  /**
   * The duration of the active interval in microseconds, or {@link C#TIME_UNSET} if the event is
   * active until the end of the period.
   */
  public final long durationUs;

  /** The resolved URI of the alternative MPD, including any MPD anchor fragment. */
  public final Uri uri;

  /** The earliest resolution time offset in microseconds before {@link #presentationTimeUs}. */
  public final long earliestResolutionTimeOffsetUs;

  /** The {@code @serviceDescriptionId}, or null if absent. */
  @Nullable public final String serviceDescriptionId;

  /** APDmax in microseconds, or {@link C#TIME_UNSET} if unbounded. Zero disables the event. */
  public final long maxDurationUs;

  /** Whether the event is executed only the first time its active interval is traversed. */
  public final boolean executeOnce;

  /** The {@code @noJump} mode. */
  public final @NoJumpMode int noJump;

  /** The {@code @skipAfter} offset in microseconds. Never negative. */
  public final long skipAfterUs;

  /** The {@code SupplementalProperty} descriptors of the presentation element. */
  public final ImmutableList<Descriptor> supplementalProperties;

  /** The replacement {@code @returnOffset} in microseconds, or {@link C#TIME_UNSET} if absent. */
  public final long returnOffsetUs;

  /** The replacement {@code @clip} value. Always true for insertion events. */
  public final boolean clip;

  /** The replacement {@code @startWithOffset} value. Always false for insertion events. */
  public final boolean startWithOffset;

  private AlternativeMpdEvent(Builder builder) {
    this.type = builder.type;
    this.id = builder.id;
    this.isUpdate = builder.type == TYPE_REPLACE && builder.isUpdate;
    this.eventStreamValue = builder.eventStreamValue;
    this.presentationTimeUs = builder.presentationTimeUs;
    this.durationUs = builder.durationUs;
    this.uri = builder.uri;
    this.earliestResolutionTimeOffsetUs = builder.earliestResolutionTimeOffsetUs;
    this.serviceDescriptionId = builder.serviceDescriptionId;
    this.maxDurationUs = builder.maxDurationUs;
    this.executeOnce = builder.executeOnce;
    this.noJump = builder.noJump;
    this.skipAfterUs = builder.skipAfterUs;
    this.supplementalProperties = builder.supplementalProperties;
    this.returnOffsetUs = builder.type == TYPE_REPLACE ? builder.returnOffsetUs : C.TIME_UNSET;
    this.clip = builder.type != TYPE_REPLACE || builder.clip;
    this.startWithOffset = builder.type == TYPE_REPLACE && builder.startWithOffset;
  }

  /** Returns a {@link Builder} initialized with the values of this instance. */
  public Builder buildUpon() {
    return new Builder(this);
  }

  @Override
  public boolean equals(@Nullable Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AlternativeMpdEvent)) {
      return false;
    }
    AlternativeMpdEvent that = (AlternativeMpdEvent) o;
    return type == that.type
        && id == that.id
        && isUpdate == that.isUpdate
        && presentationTimeUs == that.presentationTimeUs
        && durationUs == that.durationUs
        && earliestResolutionTimeOffsetUs == that.earliestResolutionTimeOffsetUs
        && maxDurationUs == that.maxDurationUs
        && executeOnce == that.executeOnce
        && noJump == that.noJump
        && skipAfterUs == that.skipAfterUs
        && returnOffsetUs == that.returnOffsetUs
        && clip == that.clip
        && startWithOffset == that.startWithOffset
        && Objects.equals(eventStreamValue, that.eventStreamValue)
        && Objects.equals(uri, that.uri)
        && Objects.equals(serviceDescriptionId, that.serviceDescriptionId)
        && Objects.equals(supplementalProperties, that.supplementalProperties);
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        type,
        id,
        isUpdate,
        eventStreamValue,
        presentationTimeUs,
        durationUs,
        uri,
        earliestResolutionTimeOffsetUs,
        serviceDescriptionId,
        maxDurationUs,
        executeOnce,
        noJump,
        skipAfterUs,
        supplementalProperties,
        returnOffsetUs,
        clip,
        startWithOffset);
  }
}
