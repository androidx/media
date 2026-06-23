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
package androidx.media3.effect;

import static androidx.media3.effect.FrameProcessorUtils.runAllAndAccumulateExceptions;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertThrows;

import androidx.media3.common.VideoFrameProcessingException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link FrameProcessorUtils}. */
@RunWith(AndroidJUnit4.class)
public final class FrameProcessorUtilsTest {

  @Test
  public void runAllAndAccumulateExceptions_noExceptions_doesNotThrow() throws Exception {
    runAllAndAccumulateExceptions(() -> {}, () -> {});
  }

  @Test
  public void runAllAndAccumulateExceptions_singleException_throwsAndWrapsException() {
    Exception exception = new Exception("Test exception");

    VideoFrameProcessingException thrown =
        assertThrows(
            VideoFrameProcessingException.class,
            () ->
                runAllAndAccumulateExceptions(
                    () -> {
                      throw exception;
                    }));

    assertThat(thrown).hasCauseThat().isEqualTo(exception);
  }

  @Test
  public void runAllAndAccumulateExceptions_multipleExceptions_throwsWithSuppressedExceptions() {
    Exception firstException = new Exception("First exception");
    Exception secondException = new Exception("Second exception");
    Exception thirdException = new Exception("Third exception");

    VideoFrameProcessingException thrown =
        assertThrows(
            VideoFrameProcessingException.class,
            () ->
                runAllAndAccumulateExceptions(
                    () -> {
                      throw firstException;
                    },
                    () -> {
                      throw secondException;
                    },
                    () -> {
                      throw thirdException;
                    }));

    assertThat(thrown).hasCauseThat().isEqualTo(firstException);
    assertThat(thrown.getSuppressed()).hasLength(2);
    assertThat(thrown.getSuppressed()[0]).hasCauseThat().isEqualTo(secondException);
    assertThat(thrown.getSuppressed()[1]).hasCauseThat().isEqualTo(thirdException);
  }

  @Test
  public void runAllAndAccumulateExceptions_exceptionInMiddle_runsSubsequentActions() {
    AtomicBoolean firstActionExecuted = new AtomicBoolean();
    AtomicBoolean secondActionExecuted = new AtomicBoolean();
    AtomicBoolean thirdActionExecuted = new AtomicBoolean();
    Exception exception = new Exception("Test exception");

    assertThrows(
        VideoFrameProcessingException.class,
        () ->
            runAllAndAccumulateExceptions(
                () -> firstActionExecuted.set(true),
                () -> {
                  secondActionExecuted.set(true);
                  throw exception;
                },
                () -> thirdActionExecuted.set(true)));

    assertThat(firstActionExecuted.get()).isTrue();
    assertThat(secondActionExecuted.get()).isTrue();
    assertThat(thirdActionExecuted.get()).isTrue();
  }

  @Test
  public void runAllAndAccumulateExceptions_nullAction_doesNotThrowOrAffectRun() throws Exception {
    AtomicBoolean actionExecuted = new AtomicBoolean();
    runAllAndAccumulateExceptions(null, () -> actionExecuted.set(true), null);
    assertThat(actionExecuted.get()).isTrue();
  }
}
