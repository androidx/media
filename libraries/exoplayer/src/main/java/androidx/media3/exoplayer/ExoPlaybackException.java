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
package androidx.media3.exoplayer;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.LOCAL_VARIABLE;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.TYPE_USE;

import android.os.Bundle;
import android.os.SystemClock;
import android.text.TextUtils;
import androidx.annotation.CheckResult;
import androidx.annotation.IntDef;
import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.C.FormatSupport;
import androidx.media3.common.Format;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSource.MediaPeriodId;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Objects;

/** Thrown when a non locally recoverable playback failure occurs. */
public final class ExoPlaybackException extends PlaybackException {

  /**
   * The type of source that produced the error. One of {@link #TYPE_SOURCE}, {@link #TYPE_RENDERER}
   * {@link #TYPE_UNEXPECTED} or {@link #TYPE_REMOTE}. Note that new types may be added in the
   * future and error handling should handle unknown type values.
   */
  // @Target list includes both 'default' targets and TYPE_USE, to ensure backwards compatibility
  // with Kotlin usages from before TYPE_USE was added.
  @UnstableApi
  @Documented
  @Retention(RetentionPolicy.SOURCE)
  @Target({FIELD, METHOD, PARAMETER, LOCAL_VARIABLE, TYPE_USE})
  @IntDef({TYPE_SOURCE, TYPE_RENDERER, TYPE_UNEXPECTED, TYPE_REMOTE})
  public @interface Type {}

  /**
   * The error occurred loading data from a {@link MediaSource}.
   *
   * <p>Call {@link #getSourceException()} to retrieve the underlying cause.
   */
  @UnstableApi public static final int TYPE_SOURCE = 0;

  /**
   * The error occurred in a {@link Renderer}.
   *
   * <p>Call {@link #getRendererException()} to retrieve the underlying cause.
   */
  @UnstableApi public static final int TYPE_RENDERER = 1;

  /**
   * The error was an unexpected {@link RuntimeException}.
   *
   * <p>Call {@link #getUnexpectedException()} to retrieve the underlying cause.
   */
  @UnstableApi public static final int TYPE_UNEXPECTED = 2;

  /**
   * The error occurred in a remote component.
   *
   * <p>Call {@link #getMessage()} to retrieve the message associated with the error.
   */
  @UnstableApi public static final int TYPE_REMOTE = 3;

  /** The {@link Type} of the playback failure. */
  @UnstableApi public final @Type int type;

  /** If {@link #type} is {@link #TYPE_RENDERER}, this is the name of the renderer. */
  @UnstableApi @Nullable public final String rendererName;

  /** If {@link #type} is {@link #TYPE_RENDERER}, this is the index of the renderer. */
  @UnstableApi public final int rendererIndex;

  /**
   * If {@link #type} is {@link #TYPE_RENDERER}, this is the {@link Format} the renderer was using
   * at the time of the exception, or null if the renderer wasn't using a {@link Format}.
   */
  @UnstableApi @Nullable public final Format rendererFormat;

  /**
   * If {@link #type} is {@link #TYPE_RENDERER}, this is the level of {@link FormatSupport} of the
   * renderer for {@link #rendererFormat}. If {@link #rendererFormat} is null, this is {@link
   * C#FORMAT_HANDLED}.
   */
  @UnstableApi public final @FormatSupport int rendererFormatSupport;

  /** The {@link MediaPeriodId} of the media associated with this error, or null if undetermined. */
  @UnstableApi @Nullable public final MediaPeriodId mediaPeriodId;

  /**
   * If {@link #type} is {@link #TYPE_RENDERER}, this field indicates whether the error may be
   * recoverable by disabling and re-enabling (but <em>not</em> resetting) the renderers. For other
   * {@link Type types} this field will always be {@code false}.
   */
  /* package */ final boolean isRecoverable;

  /**
   * Creates an instance of type {@link #TYPE_SOURCE}.
   *
   * @param cause The cause of the failure.
   * @param errorCode See {@link #errorCode}.
   * @return The created instance.
   */
  @UnstableApi
  public static ExoPlaybackException createForSource(IOException cause, int errorCode) {
    return new ExoPlaybackException(TYPE_SOURCE, cause, errorCode);
  }

  /**
   * @deprecated Use {@link #createForRenderer(Throwable, String, int, Format, int, MediaPeriodId,
   *     boolean, int)} instead.
   */
  @Deprecated
  @UnstableApi
  public static ExoPlaybackException createForRenderer(
      Throwable cause,
      String rendererName,
      int rendererIndex,
      @Nullable Format rendererFormat,
      @FormatSupport int rendererFormatSupport,
      boolean isRecoverable,
      @ErrorCode int errorCode) {
    return createForRenderer(
        cause,
        rendererName,
        rendererIndex,
        rendererFormat,
        rendererFormatSupport,
        /* mediaPeriodId= */ null,
        isRecoverable,
        errorCode);
  }

  /**
   * Creates an instance of type {@link #TYPE_RENDERER}.
   *
   * @param cause The cause of the failure.
   * @param rendererName The {@linkplain Renderer#getName() name} of the renderer in which the
   *     failure occurred.
   * @param rendererIndex The index of the renderer in which the failure occurred.
   * @param rendererFormat The {@link Format} the renderer was using at the time of the exception,
   *     or null if the renderer wasn't using a {@link Format}.
   * @param rendererFormatSupport The {@link FormatSupport} of the renderer for {@code
   *     rendererFormat}. Ignored if {@code rendererFormat} is null.
   * @param mediaPeriodId The {@link MediaPeriodId mediaPeriodId} of the media associated with this
   *     error, or null if undetermined.
   * @param isRecoverable If the failure can be recovered by disabling and re-enabling the renderer.
   * @param errorCode See {@link #errorCode}.
   * @return The created instance.
   */
  @UnstableApi
  public static ExoPlaybackException createForRenderer(
      Throwable cause,
      String rendererName,
      int rendererIndex,
      @Nullable Format rendererFormat,
      @FormatSupport int rendererFormatSupport,
      @Nullable MediaPeriodId mediaPeriodId,
      boolean isRecoverable,
      @ErrorCode int errorCode) {
    return new ExoPlaybackException(
        TYPE_RENDERER,
        cause,
        /* customMessage= */ null,
        errorCode,
        rendererName,
        rendererIndex,
        rendererFormat,
        rendererFormat == null ? C.FORMAT_HANDLED : rendererFormatSupport,
        mediaPeriodId,
        isRecoverable);
  }

  /**
   * @deprecated Use {@link #createForUnexpected(RuntimeException, int)
   *     createForUnexpected(RuntimeException, ERROR_CODE_UNSPECIFIED)} instead.
   */
  @UnstableApi
  @Deprecated
  public static ExoPlaybackException createForUnexpected(RuntimeException cause) {
    return createForUnexpected(cause, ERROR_CODE_UNSPECIFIED);
  }

  /**
   * Creates an instance of type {@link #TYPE_UNEXPECTED}.
   *
   * @param cause The cause of the failure.
   * @param errorCode See {@link #errorCode}.
   * @return The created instance.
   */
  @UnstableApi
  public static ExoPlaybackException createForUnexpected(
      RuntimeException cause, @ErrorCode int errorCode) {
    return new ExoPlaybackException(TYPE_UNEXPECTED, cause, errorCode);
  }

  /**
   * Creates an instance of type {@link #TYPE_REMOTE}.
   *
   * @param message The message associated with the error.
   * @return The created instance.
   */
  @UnstableApi
  public static ExoPlaybackException createForRemote(String message) {
    return new ExoPlaybackException(
        TYPE_REMOTE,
        /* cause= */ null,
        /* customMessage= */ message,
        ERROR_CODE_REMOTE_ERROR,
        /* rendererName= */ null,
        /* rendererIndex= */ C.INDEX_UNSET,
        /* rendererFormat= */ null,
        /* rendererFormatSupport= */ C.FORMAT_HANDLED,
        /* mediaPeriodId= */ null,
        /* isRecoverable= */ false);
  }

  private ExoPlaybackException(@Type int type, Throwable cause, @ErrorCode int errorCode) {
    this(
        type,
        cause,
        /* customMessage= */ null,
        errorCode,
        /* rendererName= */ null,
        /* rendererIndex= */ C.INDEX_UNSET,
        /* rendererFormat= */ null,
        /* rendererFormatSupport= */ C.FORMAT_HANDLED,
        /* mediaPeriodId= */ null,
        /* isRecoverable= */ false);
  }

  private ExoPlaybackException(
      @Type int type,
      @Nullable Throwable cause,
      @Nullable String customMessage,
      @ErrorCode int errorCode,
      @Nullable String rendererName,
      int rendererIndex,
      @Nullable Format rendererFormat,
      @FormatSupport int rendererFormatSupport,
      @Nullable MediaPeriodId mediaPeriodId,
      boolean isRecoverable) {
    this(
        deriveMessage(
            type,
            customMessage,
            rendererName,
            rendererIndex,
            rendererFormat,
            rendererFormatSupport),
        cause,
        errorCode,
        type,
        rendererName,
        rendererIndex,
        rendererFormat,
        rendererFormatSupport,
        mediaPeriodId,
        /* timestampMs= */ SystemClock.elapsedRealtime(),
        isRecoverable);
  }

  private ExoPlaybackException(
      String message,
      @Nullable Throwable cause,
      @ErrorCode int errorCode,
      @Type int type,
      @Nullable String rendererName,
      int rendererIndex,
      @Nullable Format rendererFormat,
      @FormatSupport int rendererFormatSupport,
      @Nullable MediaPeriodId mediaPeriodId,
      long timestampMs,
      boolean isRecoverable) {
    super(message, cause, errorCode, Bundle.EMPTY, timestampMs);
    Assertions.checkArgument(!isRecoverable || type == TYPE_RENDERER);
    Assertions.checkArgument(cause != null || type == TYPE_REMOTE);
    this.type = type;
    this.rendererName = rendererName;
    this.rendererIndex = rendererIndex;
    this.rendererFormat = rendererFormat;
    this.rendererFormatSupport = rendererFormatSupport;
    this.mediaPeriodId = mediaPeriodId;
    this.isRecoverable = isRecoverable;
  }

  /**
   * Retrieves the underlying error when {@link #type} is {@link #TYPE_SOURCE}.
   *
   * @throws IllegalStateException If {@link #type} is not {@link #TYPE_SOURCE}.
   */
  @UnstableApi
  public IOException getSourceException() {
    Assertions.checkState(type == TYPE_SOURCE);
    return (IOException) Assertions.checkNotNull(getCause());
  }

  /**
   * Retrieves the underlying error when {@link #type} is {@link #TYPE_RENDERER}.
   *
   * @throws IllegalStateException If {@link #type} is not {@link #TYPE_RENDERER}.
   */
  @UnstableApi
  public Exception getRendererException() {
    Assertions.checkState(type == TYPE_RENDERER);
    return (Exception) Assertions.checkNotNull(getCause());
  }

  /**
   * Retrieves the underlying error when {@link #type} is {@link #TYPE_UNEXPECTED}.
   *
   * @throws IllegalStateException If {@link #type} is not {@link #TYPE_UNEXPECTED}.
   */
  @UnstableApi
  public RuntimeException getUnexpectedException() {
    Assertions.checkState(type == TYPE_UNEXPECTED);
    return (RuntimeException) Assertions.checkNotNull(getCause());
  }

  @Override
  public boolean errorInfoEquals(@Nullable PlaybackException that) {
    if (!super.errorInfoEquals(that)) {
      return false;
    }
    // We know that is not null and is an ExoPlaybackException because of the super call returning
    // true.
    ExoPlaybackException other = (ExoPlaybackException) Util.castNonNull(that);
    return type == other.type
        && Objects.equals(rendererName, other.rendererName)
        && rendererIndex == other.rendererIndex
        && Objects.equals(rendererFormat, other.rendererFormat)
        && rendererFormatSupport == other.rendererFormatSupport
        && Objects.equals(mediaPeriodId, other.mediaPeriodId)
        && isRecoverable == other.isRecoverable;
  }

  /**
   * Returns a copy of this exception with the provided {@link MediaPeriodId}.
   *
   * @param mediaPeriodId The {@link MediaPeriodId}.
   * @return The copied exception.
   */
  @CheckResult
  /* package */ ExoPlaybackException copyWithMediaPeriodId(@Nullable MediaPeriodId mediaPeriodId) {
    return new ExoPlaybackException(
        Util.castNonNull(getMessage()),
        getCause(),
        errorCode,
        type,
        rendererName,
        rendererIndex,
        rendererFormat,
        rendererFormatSupport,
        mediaPeriodId,
        timestampMs,
        isRecoverable);
  }

  private static String deriveMessage(
      @Type int type,
      @Nullable String customMessage,
      @Nullable String rendererName,
      int rendererIndex,
      @Nullable Format rendererFormat,
      @FormatSupport int rendererFormatSupport) {
    String message;
    switch (type) {
      case TYPE_SOURCE:
        message = "Source error";
        break;
      case TYPE_RENDERER:
        message =
            rendererName
                + " error"
                + ", index="
                + rendererIndex
                + ", format="
                + rendererFormat
                + ", format_supported="
                + Util.getFormatSupportString(rendererFormatSupport);
        break;
      case TYPE_REMOTE:
        message = "Remote error";
        break;
      case TYPE_UNEXPECTED:
      default:
        message = "Unexpected runtime error";
        break;
    }
    if (!TextUtils.isEmpty(customMessage)) {
      message += ": " + customMessage;
    }
    return message;
  }
}
