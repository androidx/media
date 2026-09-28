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

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.res.Resources;
import android.util.TypedValue;
import androidx.media3.common.util.Util;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Tests for the GLSL shaders that are packaged as raw resources. */
@RunWith(AndroidJUnit4.class)
public final class GlslShaderResourcesTest {

  @Test
  public void loadRawResource_glslShaders_haveNoComments() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();

    ImmutableMap<String, String> shadersByName = loadGlslRawResources(context);

    assertThat(shadersByName).isNotEmpty();
    assertThat(Maps.filterValues(shadersByName, GlslShaderResourcesTest::containsComment).keySet())
        .isEmpty();
  }

  private static ImmutableMap<String, String> loadGlslRawResources(Context context)
      throws IllegalAccessException, IOException {
    Resources resources = context.getResources();
    TypedValue value = new TypedValue();
    ImmutableMap.Builder<String, String> shadersByName = ImmutableMap.builder();
    for (Field field : R.raw.class.getFields()) {
      if (Modifier.isStatic(field.getModifiers()) && field.getType() == int.class) {
        int resourceId = field.getInt(/* obj= */ null);
        resources.getValue(resourceId, value, /* resolveRefs= */ true);
        if (String.valueOf(value.string).endsWith(".glsl")) {
          shadersByName.put(field.getName(), Util.loadRawResource(context, resourceId));
        }
      }
    }
    return shadersByName.buildOrThrow();
  }

  private static boolean containsComment(String shader) {
    return shader.contains("//") || shader.contains("/*");
  }
}
