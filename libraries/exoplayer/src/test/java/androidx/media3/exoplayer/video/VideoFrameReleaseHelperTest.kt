/*
 * Copyright 2025 The Android Open Source Project
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
package androidx.media3.exoplayer.video

import android.content.Context
import android.hardware.display.DisplayManager
import android.view.Display
import androidx.media3.common.C
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.util.Random
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowDisplayManager

/** Unit tests for {@link VideoFrameReleaseHelper}. */
@RunWith(AndroidJUnit4::class)
class VideoFrameReleaseHelperTest {

  private val context: Context = ApplicationProvider.getApplicationContext()

  @Test
  fun adjustReleaseTime_60Fps60Hz_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    val testData = generateTestData(sampleFrameRate = 60f, screenRefreshRate = 60f)

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun adjustReleaseTime_30Fps60Hz_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    val testData = generateTestData(sampleFrameRate = 30f, screenRefreshRate = 60f)

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(2))
  }

  @Test
  fun adjustReleaseTime_60Fps90Hz_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 90f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    val testData = generateTestData(sampleFrameRate = 60f, screenRefreshRate = 90f)

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1, 2))
  }

  @Test
  fun adjustReleaseTime_24Fps60Hz_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    val testData = generateTestData(sampleFrameRate = 24f, screenRefreshRate = 60f)

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(2, 3))
  }

  @Test
  fun adjustReleaseTime_50Fps60Hz_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    val testData = generateTestData(sampleFrameRate = 50f, screenRefreshRate = 60f)

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1, 1, 1, 1, 2))
  }

  @Test
  fun adjustReleaseTime_smallFrameTimeVariationAtVsyncBoundary_releasesFramesSmoothly() {
    val random = Random(/* seed= */ 1234)
    updateDisplayRefreshRate(context, refreshRate = 50f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Choose a vsync offset of 10ms = half a screen refresh
    val testData =
      generateTestData(
        sampleFrameRate = 50f,
        screenRefreshRate = 50f,
        frameTimeUsModifier = { it + ((random.nextGaussian() - 0.5) * 200).toLong() },
        vsyncOffsetToFirstReleaseTimeNs = 10_000_000,
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun adjustReleaseTime_frameRateChange_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Change to 30 Hz after 500 samples by doubling the difference between frame times.
    var frameIndex = 0
    var first30FpsFrameTimeUs = 0L
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        frameTimeUsModifier = {
          frameIndex++
          if (frameIndex == 500) {
            first30FpsFrameTimeUs = it
          }
          if (frameIndex >= 500) {
            first30FpsFrameTimeUs + (it - first30FpsFrameTimeUs) * 2
          } else {
            it
          }
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 0, toIndex = 500),
      pattern = listOf(1),
    )
    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 499, toIndex = 999),
      pattern = listOf(2),
    )
  }

  @Test
  fun adjustReleaseTime_displayRefreshRateChange_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Change vsync times to 120 Hz after 500 samples by updating the time differences.
    var frameIndex = 0
    var first120FpsVsyncTimeNs = 0L
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        vsyncTimeNsModifier = {
          frameIndex++
          if (frameIndex == 500) {
            first120FpsVsyncTimeNs = it
          }
          if (frameIndex >= 500) {
            first120FpsVsyncTimeNs + (it - first120FpsVsyncTimeNs) / 2
          } else {
            it
          }
        },
      )

    val releaseTimesNs =
      adjustReleaseTimes(
        videoFrameReleaseHelper,
        testData,
        onFrameAdjusted = {
          if (it == 499) {
            updateDisplayRefreshRate(context, refreshRate = 120f)
            videoFrameReleaseHelper.setVsyncSampleTimeNs(
              getVsyncSampleTimeNs(testData.releaseTimeNs[it], testData)
            )
          }
        },
      )

    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 0, toIndex = 500),
      pattern = listOf(1),
    )
    // Note: not using fromIndex=499 as the sample at the refresh rate transition point may align
    // to new pattern by having a slightly off value (concretely: 1 - 1 - 1 - 3 - 2 - 2 - 2).
    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 500, toIndex = 999),
      pattern = listOf(2),
    )
  }

  @Test
  fun adjustReleaseTime_smallReleaseTimeDrift_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let release time drift up to 15ms, clearly closer to the next vsync
    var releaseTimeDriftNs = 0
    val testData =
      generateTestData(
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        releaseTimeNsModifier = {
          if (releaseTimeDriftNs < 15_000_000) {
            releaseTimeDriftNs += 100_000
          }
          it + releaseTimeDriftNs
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun adjustReleaseTime_smallReleaseTimeDriftWithOccasionalOutlier_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let release time drift up to 15ms, clearly closer to the next vsync.
    // Also generate occasional outlier frame data with +- 2ms
    var releaseTimeDriftNs = 0
    var frameIndex = 0
    val testData =
      generateTestData(
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        frameTimeUsModifier = {
          frameIndex++
          if (frameIndex % 50 == 30) {
            it + 2_000
          } else if (frameIndex % 50 == 32) {
            it - 2000
          } else {
            it
          }
        },
        releaseTimeNsModifier = {
          if (releaseTimeDriftNs < 15_000_000) {
            releaseTimeDriftNs += 100_000
          }
          it + releaseTimeDriftNs
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun adjustReleaseTime_largerIncrementalReleaseTimeDrift_freezesSingleFrameDuringReset() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let release time drift up continuously (up to 1000 * 30us = 30ms in total), which should
    // cause an A/V re-sync at least once.
    var releaseTimeDriftNs = 0
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        releaseTimeNsModifier = {
          releaseTimeDriftNs += 30_000
          it + releaseTimeDriftNs
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertThat(pulldownPattern.count { it == 2 }).isEqualTo(1)
    assertThat(pulldownPattern.count { it == 1 }).isEqualTo(pulldownPattern.size - 1)
  }

  @Test
  fun adjustReleaseTime_suddenReleaseTimeJump_freezesSingleFrameAtJump() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    var frameIndex = 0
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        releaseTimeNsModifier = {
          frameIndex++
          if (frameIndex >= 500) it + 30_000_000 else it
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertThat(pulldownPattern.count { it == 1 }).isEqualTo(pulldownPattern.size - 1)
    assertThat(pulldownPattern.indexOfFirst { it != 1 }).isWithin(2).of(499)
  }

  @Test
  fun adjustReleaseTime_positiveVsyncTimeDrift_skipsSingleFrameAtRollover() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let the vsync time drift slowly, rolling after 800 samples
    var vsyncDriftNs = 0L
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        vsyncTimeNsModifier = {
          vsyncDriftNs += C.NANOS_PER_SECOND / 60 / 800
          it + vsyncDriftNs
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertThat(pulldownPattern.count { it == 0 }).isEqualTo(1)
    assertThat(pulldownPattern.count { it == 1 }).isEqualTo(pulldownPattern.size - 1)
  }

  @Test
  fun adjustReleaseTime_negativeVsyncTimeDrift_freezesSingleFrameAtRollover() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let the vsync time drift slowly, rolling after 800 samples
    var vsyncDriftNs = 0L
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        vsyncTimeNsModifier = {
          vsyncDriftNs += C.NANOS_PER_SECOND / 60 / 800
          it - vsyncDriftNs
        },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertThat(pulldownPattern.count { it == 2 }).isEqualTo(1)
    assertThat(pulldownPattern.count { it == 1 }).isEqualTo(pulldownPattern.size - 1)
  }

  @Test
  fun adjustReleaseTime_smallVsyncTimeVariationWithFramesAtVsyncBoundary_releasesFramesSmoothly() {
    val random = Random(/* seed= */ 1234)
    updateDisplayRefreshRate(context, refreshRate = 50f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Choose a vsync offset of 10ms = half a screen refresh
    val testData =
      generateTestData(
        sampleFrameRate = 50f,
        screenRefreshRate = 50f,
        vsyncOffsetToFirstReleaseTimeNs = 10_000_000,
        vsyncTimeNsModifier = { it + ((random.nextGaussian() - 0.5) * 500_000).toLong() },
      )

    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun adjustReleaseTime_2Speed_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Adjust release times to account for the faster playing audio.
    val testData =
      generateTestData(
        sampleFrameRate = 60f,
        releaseTimeNsModifier = { it / 2 },
        screenRefreshRate = 60f,
      )

    videoFrameReleaseHelper.onPlaybackSpeed(2f)
    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(0, 1))
  }

  @Test
  fun adjustReleaseTime_point5Speed_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Adjust release times to account for the slower playing audio.
    val testData =
      generateTestData(
        sampleFrameRate = 60f,
        releaseTimeNsModifier = { it * 2 },
        screenRefreshRate = 60f,
      )

    videoFrameReleaseHelper.onPlaybackSpeed(0.5f)
    val releaseTimesNs = adjustReleaseTimes(videoFrameReleaseHelper, testData)

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(2))
  }

  @Test
  fun adjustReleaseTime_speedChange_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Adjust release times to account for the faster and then slower playing audio.
    var frameIndex = 0
    var firstPoint5SpeedOriginalReleaseTimeNs = 0L
    var firstPoint5SpeedAdjustedReleaseTimeNs = 0L
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        releaseTimeNsModifier = {
          frameIndex++
          if (frameIndex == 500) {
            firstPoint5SpeedOriginalReleaseTimeNs = it
            firstPoint5SpeedAdjustedReleaseTimeNs = it / 2
          }
          if (frameIndex >= 500) {
            firstPoint5SpeedAdjustedReleaseTimeNs + (it - firstPoint5SpeedOriginalReleaseTimeNs) * 2
          } else {
            it / 2
          }
        },
        screenRefreshRate = 60f,
      )

    videoFrameReleaseHelper.onPlaybackSpeed(2f)
    val releaseTimesNs =
      adjustReleaseTimes(
        videoFrameReleaseHelper,
        testData,
        onFrameAdjusted = {
          if (it == 499) {
            videoFrameReleaseHelper.onPlaybackSpeed(0.5f)
          }
        },
      )

    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 0, toIndex = 500),
      pattern = listOf(0, 1),
    )
    assertPullDownPattern(
      testData,
      releaseTimesNs.subList(fromIndex = 499, toIndex = 999),
      pattern = listOf(2),
    )
  }

  @Test
  fun adjustReleaseTime_tinySpeedChangeAndSmallReleaseTimeDrift_releasesFramesSmoothly() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Adjust release times to account for slower playing audio in the second half. Also add a small
    // release time drift up to 15ms to check if it interacts well with the speed change.
    var frameIndex = 0
    var firstSlowerSpeedReleaseTimeNs = 0L
    var releaseTimeDriftNs = 0
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        releaseTimeNsModifier = {
          if (releaseTimeDriftNs < 15_000_000) {
            releaseTimeDriftNs += 100_000
          }
          frameIndex++
          if (frameIndex == 500) {
            firstSlowerSpeedReleaseTimeNs = it
          }
          if (frameIndex >= 500) {
            firstSlowerSpeedReleaseTimeNs +
              ((it - firstSlowerSpeedReleaseTimeNs) / 0.9999).toLong() +
              releaseTimeDriftNs
          } else {
            it + releaseTimeDriftNs
          }
        },
        screenRefreshRate = 60f,
      )

    val releaseTimesNs =
      adjustReleaseTimes(
        videoFrameReleaseHelper,
        testData,
        onFrameAdjusted = {
          if (it == 499) {
            videoFrameReleaseHelper.onPlaybackSpeed(0.9999f)
          }
        },
      )

    assertPullDownPattern(testData, releaseTimesNs, pattern = listOf(1))
  }

  @Test
  fun onPositionReset_largerIncrementalReleaseTimeDrift_freezesSingleFrameAtTheReset() {
    updateDisplayRefreshRate(context, refreshRate = 60f)
    val videoFrameReleaseHelper = VideoFrameReleaseHelper(context)
    videoFrameReleaseHelper.onStarted()
    // Let release time drift up continuously (up to 1000 * 30us = 30ms in total), which should
    // cause an A/V re-sync at least once. We want to assert the reported position reset forces
    // this to happen at the time of the method call.
    var releaseTimeDriftNs = 0
    val testData =
      generateTestData(
        sampleCount = 1000,
        sampleFrameRate = 60f,
        screenRefreshRate = 60f,
        releaseTimeNsModifier = {
          releaseTimeDriftNs += 30_000
          it + releaseTimeDriftNs
        },
      )

    val releaseTimesNs =
      adjustReleaseTimes(
        videoFrameReleaseHelper,
        testData,
        onFrameAdjusted = { if (it == 499) videoFrameReleaseHelper.onPositionReset() },
      )

    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertThat(pulldownPattern.count { it == 1 }).isEqualTo(pulldownPattern.size - 1)
    assertThat(pulldownPattern.indexOfFirst { it != 1 }).isEqualTo(499)
  }

  /**
   * Generate all 3 independent types of input data for the frame release helper:
   * <ul>
   * <li>The frame timestamps: frame rate, a fixed offset and custom modification
   * <li>The A/V sync release time offset: fixed offset to audio timestamp and custom modification
   * <li>The screen refresh vsyncs: screen refresh rate, relative offset to first sample and custom
   *   modifications
   * </ul>
   */
  private fun generateTestData(
    sampleFrameRate: Float,
    sampleCount: Int = 1000,
    fixedFrameTimeOffsetUs: Long = 123456789123L,
    frameTimeUsModifier: (Long) -> Long = { t -> t },
    fixedReleaseTimeOffsetNs: Long = 987654321234567L,
    releaseTimeNsModifier: (Long) -> Long = { t -> t },
    screenRefreshRate: Float,
    vsyncOffsetToFirstReleaseTimeNs: Long = 0,
    vsyncTimeNsModifier: (Long) -> Long = { t -> t },
  ): TestData {
    val frameTimesUs = buildList {
      for (i in 0..<sampleCount) {
        val baseFrameTimeUs = (i * C.MICROS_PER_SECOND / sampleFrameRate).toLong()
        add(frameTimeUsModifier.invoke(baseFrameTimeUs + fixedFrameTimeOffsetUs))
      }
    }
    val releaseTimesNs = buildList {
      for (frameTimeUs in frameTimesUs) {
        add(releaseTimeNsModifier.invoke(frameTimeUs * 1000 + fixedReleaseTimeOffsetNs))
      }
    }
    val vsyncDurationNs = (C.NANOS_PER_SECOND / screenRefreshRate).toLong()
    val firstVsyncNs =
      releaseTimesNs.first() - 2 * vsyncDurationNs + vsyncOffsetToFirstReleaseTimeNs
    val lastReleaseTimeNs = releaseTimesNs.last()
    val vsyncTimesNs = buildList {
      var vsyncCount = 0
      var vsyncTimeNs = firstVsyncNs
      while (vsyncTimeNs < lastReleaseTimeNs + 2 * vsyncDurationNs) {
        add(vsyncTimeNs)
        vsyncCount++
        val vsyncTimeUnmodifiedNs =
          firstVsyncNs + (vsyncCount * C.NANOS_PER_SECOND / screenRefreshRate).toLong()
        vsyncTimeNs = vsyncTimeNsModifier.invoke(vsyncTimeUnmodifiedNs)
      }
    }
    return TestData(frameTimesUs, releaseTimesNs, vsyncTimesNs)
  }

  /**
   * Run the frame release time adjustment with the test data.
   *
   * <p>Repeatedly calls [VideoFrameReleaseHelper.onNextFrame],
   * [VideoFrameReleaseHelper.adjustReleaseTime] and regularly sets a new vsync sample time.
   */
  private fun adjustReleaseTimes(
    videoFrameReleaseHelper: VideoFrameReleaseHelper,
    testData: TestData,
    onFrameAdjusted: (Int) -> Unit = {},
  ): List<Long> = buildList {
    var nextVsyncUpdateTimeNs = testData.releaseTimeNs.first()
    for (i in 0..<testData.releaseTimeNs.size) {
      if (testData.releaseTimeNs[i] >= nextVsyncUpdateTimeNs) {
        videoFrameReleaseHelper.setVsyncSampleTimeNs(
          getVsyncSampleTimeNs(testData.releaseTimeNs[i], testData)
        )
        nextVsyncUpdateTimeNs += VideoFrameReleaseHelper.VSYNC_SAMPLE_UPDATE_PERIOD_MS * 1_000_000
      }
      videoFrameReleaseHelper.onNextFrame(testData.frameTimeUs[i])
      add(
        videoFrameReleaseHelper.adjustReleaseTime(
          testData.releaseTimeNs[i],
          testData.frameTimeUs[i],
        )
      )
      onFrameAdjusted(i)
    }
  }

  /**
   * Asserts the adjusted release times match a given pull down pattern (regular pattern of diffs
   * between screen vsync indices).
   */
  private fun assertPullDownPattern(
    testData: TestData,
    releaseTimesNs: List<Long>,
    pattern: List<Int>,
  ) {
    val pulldownPattern = getPullDownPattern(testData, releaseTimesNs)
    assertWithMessage("vsync index diffs don't match pattern $pattern: $pulldownPattern")
      .that(matchesPattern(pulldownPattern, pattern))
      .isTrue()
  }

  /**
   * Returns the pull down patter of the given adjusted release times (regular pattern of diffs
   * between screen vsync indices).
   */
  private fun getPullDownPattern(testData: TestData, releaseTimesNs: List<Long>): List<Int> {
    val vsyncIndices = buildList {
      for (releaseTimeNs in releaseTimesNs) {
        val vsyncIndexBefore = getVsyncIndexBefore(releaseTimeNs, testData)
        // Frames are only released for a vsync if scheduled up to around half the vsync duration
        // before display time.
        val vsyncTimeBeforeNs = testData.vsyncTimesNs[vsyncIndexBefore]
        val vsyncTimeAfterNs = testData.vsyncTimesNs[vsyncIndexBefore + 1]
        if (releaseTimeNs < vsyncTimeBeforeNs + (vsyncTimeAfterNs - vsyncTimeBeforeNs) / 2) {
          add(vsyncIndexBefore + 1)
        } else {
          add(vsyncIndexBefore + 2)
        }
      }
    }
    return vsyncIndices.zipWithNext().map { (a, b) -> b - a }
  }

  private fun getVsyncSampleTimeNs(releaseTimeNs: Long, testData: TestData): Long =
    testData.vsyncTimesNs[getVsyncIndexBefore(releaseTimeNs, testData)]

  private fun getVsyncIndexBefore(releaseTimeNs: Long, testData: TestData): Int {
    val binarySearchResult = testData.vsyncTimesNs.binarySearch(releaseTimeNs)
    return if (binarySearchResult > 0) binarySearchResult - 1 else -(binarySearchResult + 2)
  }

  private fun matchesPattern(list: List<Int>, pattern: List<Int>): Boolean {
    for (patternStartIndex in 0..<pattern.size) {
      if (matchesPattern(list, pattern, patternStartIndex)) {
        return true
      }
    }
    return false
  }

  private fun matchesPattern(list: List<Int>, pattern: List<Int>, patternStartIndex: Int): Boolean {
    for (i in 0..<list.size) {
      if (list[i] != pattern[(i + patternStartIndex) % pattern.size]) {
        return false
      }
    }
    return true
  }

  private fun updateDisplayRefreshRate(context: Context, refreshRate: Float) {
    val displayManager = context.getSystemService(DisplayManager::class.java)
    val defaultDisplay = displayManager.getDisplay(Display.DEFAULT_DISPLAY)
    shadowOf(defaultDisplay).setRefreshRate(refreshRate)
    // This call is needed to trigger DisplayListener.onDisplayChanged.
    ShadowDisplayManager.changeDisplay(Display.DEFAULT_DISPLAY, RuntimeEnvironment.getQualifiers())
  }

  data class TestData(
    val frameTimeUs: List<Long>,
    val releaseTimeNs: List<Long>,
    val vsyncTimesNs: List<Long>,
  )
}
