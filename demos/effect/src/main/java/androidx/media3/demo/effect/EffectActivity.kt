/*
 * Copyright 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.demo.effect

import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.dimensionResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.demo.effect.ui.COLORS
import androidx.media3.demo.effect.ui.ColorsDropDownMenu
import androidx.media3.demo.effect.ui.DropdownControlItem
import androidx.media3.demo.effect.ui.InputSelector
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.google.common.collect.ImmutableList
import java.util.Locale
import kotlinx.coroutines.launch

class EffectActivity : ComponentActivity() {

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val playlistHolderList = mutableStateOf<List<PlaylistHolder>>(emptyList())
    lifecycleScope.launch {
      playlistHolderList.value =
        loadPlaylistsFromJson(JSON_FILENAME, this@EffectActivity, "EffectActivity")
    }
    setContent { EffectDemo(playlistHolderList.value) }
  }

  @OptIn(UnstableApi::class)
  @Composable
  private fun EffectDemo(playlistHolderList: List<PlaylistHolder>) {
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val exoPlayer by remember {
      mutableStateOf(ExoPlayer.Builder(context).build().apply { playWhenReady = true })
    }
    var effectsEnabled by remember { mutableStateOf(false) }

    Scaffold(
      modifier = Modifier.fillMaxSize(),
      snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { paddingValues ->
      Column(
        modifier = Modifier.fillMaxWidth().padding(paddingValues),
        verticalArrangement = Arrangement.Top,
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        InputSelector(
          playlistHolderList,
          onException = { message ->
            coroutineScope.launch { snackbarHostState.showSnackbar(message) }
          },
        ) { mediaItems ->
          effectsEnabled = true
          exoPlayer.apply {
            setMediaItems(mediaItems)
            setVideoEffects(emptyList())
            prepare()
          }
        }
        PlayerScreen(exoPlayer)
        EffectControls(
          effectsEnabled,
          onApplyEffectsClicked = { videoEffects ->
            exoPlayer.apply {
              setVideoEffects(videoEffects)
              prepare()
            }
          },
        )
      }
    }
  }

  @Composable
  private fun PlayerScreen(exoPlayer: ExoPlayer) {
    val context = LocalContext.current
    AndroidView(
      factory = { PlayerView(context).apply { player = exoPlayer } },
      modifier =
        Modifier.height(dimensionResource(id = R.dimen.android_view_height))
          .padding(all = dimensionResource(id = R.dimen.regular_padding)),
    )
  }

  @OptIn(UnstableApi::class)
  @Composable
  private fun EffectControls(enabled: Boolean, onApplyEffectsClicked: (List<Effect>) -> Unit) {
    var effectControlsState by remember {
      mutableStateOf(
        EffectControlsState(lottieOverlayName = getString(R.string.lottie_effect_name_counter))
      )
    }

    Button(
      enabled = enabled && effectControlsState.effectsChanged,
      onClick = {
        val effectsList = mutableListOf<Effect>()

        if (effectControlsState.contrastValue != 0f) {
          effectsList += Contrast(effectControlsState.contrastValue)
        }

        val overlaysBuilder = ImmutableList.builder<TextureOverlay>()
        if (effectControlsState.confettiOverlayChecked) {
          overlaysBuilder.add(ConfettiOverlay())
        }

        if (effectControlsState.clockOverlayChecked) {
          overlaysBuilder.add(ClockOverlay())
        }

        if (effectControlsState.lottieOverlayChecked) {
          val lottieEffect = lottieOverlayOptions[effectControlsState.lottieOverlayName]
          lottieEffect?.let { effectsList += lottieEffect }
        }

        val textOverlayText = effectControlsState.textOverlayText
        if (effectControlsState.textOverlayChecked && textOverlayText != null) {
          val spannableOverlayText = SpannableString(textOverlayText)
          spannableOverlayText.setSpan(
            ForegroundColorSpan(effectControlsState.textOverlayColor.toArgb()),
            /* start= */ 0,
            textOverlayText.length,
            Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
          )
          val staticOverlaySettings =
            StaticOverlaySettings.Builder()
              .setAlphaScale(effectControlsState.textOverlayAlpha)
              .build()
          overlaysBuilder.add(
            TextOverlay.createStaticTextOverlay(spannableOverlayText, staticOverlaySettings)
          )
        }
        effectsList += OverlayEffect(overlaysBuilder.build())

        onApplyEffectsClicked(effectsList)
        effectControlsState = effectControlsState.copy(effectsChanged = false)
      },
    ) {
      Text(text = stringResource(id = R.string.apply_effects))
    }

    EffectControlsList(enabled, effectControlsState) { newEffectControlsState ->
      effectControlsState = newEffectControlsState
    }
  }

  @Composable
  private fun EffectControlsList(
    enabled: Boolean,
    effectControlsState: EffectControlsState,
    onEffectControlsStateChange: (EffectControlsState) -> Unit,
  ) {
    LazyColumn(Modifier.padding(vertical = dimensionResource(id = R.dimen.small_padding))) {
      item {
        EffectItem(
          name = stringResource(id = R.string.contrast),
          enabled = enabled,
          onCheckedChange = {
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, contrastValue = 0f)
            )
          },
        ) {
          Row {
            Text(
              text = "%.2f".format(effectControlsState.contrastValue),
              style = MaterialTheme.typography.bodyLarge,
              modifier = Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
            )
            Slider(
              value = effectControlsState.contrastValue,
              onValueChange = { newContrastValue ->
                val newRoundedContrastValue = "%.2f".format(Locale.ROOT, newContrastValue).toFloat()
                onEffectControlsStateChange(
                  effectControlsState.copy(
                    effectsChanged = true,
                    contrastValue = newRoundedContrastValue,
                  )
                )
              },
              valueRange = -1f..1f,
              modifier = Modifier.weight(4f),
            )
          }
        }
      }
      item {
        EffectItem(
          name = stringResource(R.string.confetti_overlay),
          enabled = enabled,
          onCheckedChange = { checked ->
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, confettiOverlayChecked = checked)
            )
          },
        )
      }
      item {
        EffectItem(
          name = stringResource(R.string.clock_overlay),
          enabled = enabled,
          onCheckedChange = { checked ->
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, clockOverlayChecked = checked)
            )
          },
        )
      }
      item {
        EffectItem(
          name = stringResource(R.string.lottie_overlay),
          enabled = enabled,
          onCheckedChange = { checked ->
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = true, lottieOverlayChecked = checked)
            )
          },
        ) {
          Column {
            Row {
              DropdownControlItem(
                title = stringResource(R.string.lottie_asset),
                value =
                  effectControlsState.lottieOverlayName ?: lottieOverlayOptions.keys.toList()[0],
                options = lottieOverlayOptions.keys.toList(),
                onValueChange = { value ->
                  onEffectControlsStateChange(
                    effectControlsState.copy(effectsChanged = true, lottieOverlayName = value)
                  )
                },
              )
            }
          }
        }
      }
      item {
        EffectItem(
          name = stringResource(R.string.custom_text_overlay),
          enabled = enabled,
          onCheckedChange = { checked ->
            onEffectControlsStateChange(
              effectControlsState.copy(effectsChanged = !checked, textOverlayChecked = checked)
            )
          },
        ) {
          Column {
            OutlinedTextField(
              value = effectControlsState.textOverlayText ?: "",
              onValueChange = { newTextOverlayText ->
                onEffectControlsStateChange(
                  effectControlsState.copy(
                    effectsChanged = true,
                    textOverlayText = newTextOverlayText.ifEmpty { null },
                  )
                )
              },
              label = { Text(stringResource(R.string.text)) },
              singleLine = true,
              modifier =
                Modifier.fillMaxWidth().padding(bottom = dimensionResource(R.dimen.large_padding)),
            )
            Row {
              ColorsDropDownMenu(effectControlsState.textOverlayColor) { color ->
                onEffectControlsStateChange(
                  effectControlsState.copy(
                    effectsChanged = effectControlsState.textOverlayText != null,
                    textOverlayColor = color,
                  )
                )
              }
            }
            Row {
              Text(
                text =
                  stringResource(R.string.alpha) +
                    " = %.2f".format(effectControlsState.textOverlayAlpha),
                style = MaterialTheme.typography.bodyLarge,
                modifier =
                  Modifier.padding(dimensionResource(id = R.dimen.large_padding)).weight(1f),
              )
              Slider(
                value = effectControlsState.textOverlayAlpha,
                onValueChange = { newAlphaValue ->
                  val newRoundedAlphaValue = "%.2f".format(Locale.ROOT, newAlphaValue).toFloat()
                  onEffectControlsStateChange(
                    effectControlsState.copy(
                      effectsChanged = effectControlsState.textOverlayText != null,
                      textOverlayAlpha = newRoundedAlphaValue,
                    )
                  )
                },
                valueRange = 0f..1f,
                modifier = Modifier.weight(2f),
              )
            }
          }
        }
      }
    }
  }

  private val lottieOverlayOptions: Map<String, Effect> by lazy {
    buildMap {
      LottieEffectFactory.buildAvailableEffects(application).forEach { (name, effect) ->
        put(name, effect)
      }
    }
  }

  @Composable
  fun EffectItem(
    name: String,
    enabled: Boolean,
    onCheckedChange: (Boolean) -> Unit = {},
    content: @Composable () -> Unit = {},
  ) {
    var checked by rememberSaveable { mutableStateOf(false) }
    Card(
      modifier =
        Modifier.padding(
            vertical = dimensionResource(id = R.dimen.small_padding),
            horizontal = dimensionResource(id = R.dimen.regular_padding),
          )
          .clickable(enabled = enabled && !checked) {
            checked = !checked
            onCheckedChange(checked)
          }
    ) {
      Column(
        Modifier.padding(dimensionResource(id = R.dimen.large_padding))
          .animateContentSize(animationSpec = tween(durationMillis = 200, easing = LinearEasing))
      ) {
        Row {
          Column(Modifier.weight(1f).padding(dimensionResource(id = R.dimen.large_padding))) {
            Text(text = name, style = MaterialTheme.typography.bodyLarge)
          }
          Checkbox(
            enabled = enabled,
            checked = checked,
            onCheckedChange = {
              checked = !checked
              onCheckedChange(checked)
            },
          )
        }
        if (checked) {
          content()
        }
      }
    }
  }

  private data class EffectControlsState(
    val effectsChanged: Boolean = false,
    val contrastValue: Float = 0f,
    val confettiOverlayChecked: Boolean = false,
    val textOverlayChecked: Boolean = false,
    val clockOverlayChecked: Boolean = false,
    val lottieOverlayChecked: Boolean = false,
    val textOverlayText: String? = null,
    val textOverlayColor: Color = COLORS[0],
    val textOverlayAlpha: Float = 1f,
    val lottieOverlayName: String? = null,
  )

  private companion object {
    const val JSON_FILENAME = "media.playlist.json"
  }
}
