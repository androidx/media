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
package androidx.media3.demo.composition.ui

import android.os.Build.VERSION.SDK_INT
import android.os.LocaleList
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.media3.demo.composition.R
import androidx.media3.demo.composition.data.Gap
import androidx.media3.demo.composition.data.Item
import androidx.media3.demo.composition.data.Media
import androidx.media3.demo.composition.ui.theme.spacing
import java.util.Locale
import kotlin.time.DurationUnit
import kotlin.time.toDuration

@Composable
internal fun VideoSequenceDialog(
  onDismissRequest: () -> Unit,
  itemOptions: List<Item>,
  addSelectedVideo: (Int) -> Unit,
) {
  Dialog(onDismissRequest) {
    Card(
      modifier = Modifier.fillMaxSize().padding(MaterialTheme.spacing.mini),
      shape = RoundedCornerShape(16.dp),
    ) {
      Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(
          text = stringResource(R.string.select_items),
          fontWeight = FontWeight.Bold,
          modifier = Modifier.padding(0.dp, MaterialTheme.spacing.small),
        )
        LazyColumn(
          verticalArrangement = Arrangement.spacedBy(2.dp),
          modifier = Modifier.weight(1f).padding(MaterialTheme.spacing.small, 0.dp),
        ) {
          itemsIndexed(itemOptions) { index, item ->
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(2.dp),
              modifier = Modifier.fillMaxWidth(),
            ) {
              FilledIconButton(onClick = { addSelectedVideo(index) }) {
                Icon(
                  painterResource(R.drawable.add),
                  contentDescription = stringResource(R.string.add_item),
                )
              }
              val title =
                when (item) {
                  is Media -> item.title
                  is Gap -> stringResource(R.string.gap_label)
                }
              val duration = item.durationUs.toDuration(DurationUnit.MICROSECONDS)
              val durationString =
                String.format(
                  getLocaleWithSdk(),
                  "%02d:%02d",
                  duration.inWholeMinutes,
                  duration.inWholeSeconds % 60,
                )
              Text(text = stringResource(R.string.item_with_duration, title, durationString))
            }
          }
        }
        Button(
          { onDismissRequest() },
          modifier = Modifier.padding(0.dp, MaterialTheme.spacing.mini),
        ) {
          Text(text = stringResource(R.string.ok))
        }
      }
    }
  }
}

private fun getLocaleWithSdk(): Locale {
  return if (SDK_INT >= 24) {
    LocaleList.getDefault().get(0) ?: Locale.getDefault()
  } else {
    Locale.getDefault()
  }
}
