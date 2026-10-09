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

import static com.google.common.truth.Truth.assertThat;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link AlternativeMpdEvent}. */
@RunWith(AndroidJUnit4.class)
public final class AlternativeMpdEventTest {

  @Test
  public void build_withTypeInsert_ignoresReplaceOnlyFields() {
    AlternativeMpdEvent.Builder builder =
        new AlternativeMpdEvent.Builder(
                AlternativeMpdEvent.TYPE_INSERT,
                /* id= */ 1,
                Uri.parse("https://ads.example.com/ad.mpd"))
            .setIsUpdate(true)
            .setReturnOffsetUs(1_000L)
            .setClip(false)
            .setStartWithOffset(true);

    AlternativeMpdEvent event = builder.build();

    assertThat(event.isUpdate).isFalse();
    assertThat(event.returnOffsetUs).isEqualTo(C.TIME_UNSET);
    assertThat(event.clip).isTrue();
    assertThat(event.startWithOffset).isFalse();
  }

  @Test
  public void equalsAndHashCode_allFields_comparesEqualAndProducesSameHash() {
    AlternativeMpdEvent base =
        new AlternativeMpdEvent.Builder(
                AlternativeMpdEvent.TYPE_REPLACE,
                /* id= */ 1,
                Uri.parse("https://ads.example.com/ad.mpd"))
            .setIsUpdate(true)
            .setEventStreamValue("v")
            .setPresentationTimeUs(10_000_000)
            .setDurationUs(5_000_000)
            .setEarliestResolutionTimeOffsetUs(20_000_000)
            .setServiceDescriptionId("sd")
            .setMaxDurationUs(15_000_000)
            .setExecuteOnce(true)
            .setNoJump(AlternativeMpdEvent.NO_JUMP_ALL)
            .setSkipAfterUs(3_000_000)
            .setSupplementalProperties(
                ImmutableList.of(new Descriptor("urn:s", "val", /* id= */ "1")))
            .setReturnOffsetUs(4_000_000)
            .setClip(false)
            .setStartWithOffset(true)
            .build();
    AlternativeMpdEvent defaultReplace =
        new AlternativeMpdEvent.Builder(
                AlternativeMpdEvent.TYPE_REPLACE,
                /* id= */ 1,
                Uri.parse("https://ads.example.com/ad.mpd"))
            .build();
    AlternativeMpdEvent defaultInsert =
        new AlternativeMpdEvent.Builder(
                AlternativeMpdEvent.TYPE_INSERT,
                /* id= */ 1,
                Uri.parse("https://ads.example.com/ad.mpd"))
            .build();

    AlternativeMpdEvent copy = base.buildUpon().build();

    assertThat(copy).isEqualTo(base);
    assertThat(copy.hashCode()).isEqualTo(base.hashCode());
    assertThat(defaultInsert).isNotEqualTo(defaultReplace);
    assertThat(base.buildUpon().setId(2).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setIsUpdate(false).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setEventStreamValue("other").build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setPresentationTimeUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setDurationUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setUri(Uri.parse("https://other.example.com")).build())
        .isNotEqualTo(base);
    assertThat(base.buildUpon().setEarliestResolutionTimeOffsetUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setServiceDescriptionId("other").build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setMaxDurationUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setExecuteOnce(false).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setNoJump(AlternativeMpdEvent.NO_JUMP_NONE).build())
        .isNotEqualTo(base);
    assertThat(base.buildUpon().setSkipAfterUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setSupplementalProperties(ImmutableList.of()).build())
        .isNotEqualTo(base);
    assertThat(base.buildUpon().setReturnOffsetUs(99).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setClip(true).build()).isNotEqualTo(base);
    assertThat(base.buildUpon().setStartWithOffset(false).build()).isNotEqualTo(base);
  }
}
