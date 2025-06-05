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
package androidx.media3.testapp.controller

import androidx.media3.common.Player
import androidx.media3.session.SessionCommand
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.lang.reflect.Field
import java.lang.reflect.Modifier
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MediaIntToStringTest {

  @Test
  fun playbackStateMap_hasExpectedKeysAndValues() {
    assertThat(MediaIntToString.playbackStateMap)
      .isEqualTo(getValuesToFieldNameMap(Player::class.java, "STATE_"))
  }

  @Test
  fun getPlayerCommandMap() {
    assertThat(MediaIntToString.playerCommandMap)
      .isEqualTo(getValuesToFieldNameMap(Player::class.java, "COMMAND_"))
  }

  @Test
  fun getSessionCommandMap() {
    val expectedSessionCommandMap =
      getValuesToFieldNameMap(SessionCommand::class.java, "COMMAND_")
        .mapValues { (_, value) -> value.replace("COMMAND_CODE_", "COMMAND_") }
        .filterValues { it != "COMMAND_CUSTOM" }

    assertThat(MediaIntToString.sessionCommandMap).isEqualTo(expectedSessionCommandMap)
  }

  private fun <T> getValuesToFieldNameMap(clazz: Class<T>, prefix: String): Map<Any?, String> {
    val fields =
      clazz.fields.filter { Modifier.isStatic(it.modifiers) && it.name.startsWith(prefix) }
    val valuesToFieldMap = mutableMapOf<Any?, Field>()
    for (field in fields) {
      valuesToFieldMap.compute(field.get(null)) { _, existingField ->
        if (existingField == null || isDeprecated(existingField)) {
          field
        } else if (isDeprecated(field)) {
          existingField
        } else
          throw IllegalStateException(
            "At least two non-deprecated fields with the same value:\n$existingField\n$field"
          )
      }
    }
    return valuesToFieldMap.mapValues { (_, value) -> value.name }
  }

  private fun isDeprecated(field: Field): Boolean {
    return field.getAnnotation(java.lang.Deprecated::class.java) != null
  }
}
