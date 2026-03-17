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
package androidx.media3.transformer;

import static androidx.media3.test.utils.AssetInfo.MP4_ADVANCED_ASSET;

import android.content.Context;
import android.view.SurfaceView;
import androidx.media3.common.MediaItem;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableList;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.runner.RunWith;

/** End-to-end instrumentation test for {@link Transformer} debug preview. */
@RunWith(AndroidJUnit4.class)
public class TransformerDebugPreviewTest {
  private final Context context = ApplicationProvider.getApplicationContext();
  @Rule public final TestName testName = new TestName();

  @Rule
  public ActivityScenarioRule<SurfaceTestActivity> rule =
      new ActivityScenarioRule<>(SurfaceTestActivity.class);

  private SurfaceView surfaceView;
  private String testId;

  @Before
  public void setUp() {
    testId = testName.getMethodName();
    rule.getScenario().onActivity(activity -> surfaceView = activity.getSurfaceView());
  }

  @After
  public void tearDown() {
    rule.getScenario().close();
  }

  @Test
  public void export_withDefaultFrameDroppingAndPresentationWithDebugPreview_succeeds()
      throws Exception {
    Transformer transformer =
        new Transformer.Builder(context)
            .setDebugViewProvider((width, height) -> surfaceView)
            .build();
    EditedMediaItem item =
        new EditedMediaItem.Builder(MediaItem.fromUri(MP4_ADVANCED_ASSET.uri))
            .setEffects(
                new Effects(
                    /* audioProcessors= */ ImmutableList.of(),
                    /* videoEffects= */ ImmutableList.of(
                        FrameDropEffect.createDefaultFrameDropEffect(30f),
                        Presentation.createForShortSide(480))))
            .build();

    new TransformerAndroidTestRunner.Builder(context, transformer).build().run(testId, item);
  }
}
