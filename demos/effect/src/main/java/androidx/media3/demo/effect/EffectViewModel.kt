/*
 * Copyright 2026 The Android Open Source Project
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

import android.app.Application
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import androidx.annotation.OptIn
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Contrast
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.StaticOverlaySettings
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import androidx.media3.exoplayer.ExoPlayer
import com.google.common.collect.ImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [AndroidViewModel] for the effect demo application.
 *
 * This ViewModel manages the [ExoPlayer] instance, loads playlists, and provides methods to update
 * and apply video effects based on the current [EffectUiState].
 */
@OptIn(UnstableApi::class)
internal class EffectViewModel(application: Application) : AndroidViewModel(application) {

  private val _playlistHolderList = MutableStateFlow<List<PlaylistHolder>>(emptyList())
  /** A [StateFlow] emitting the list of available playlists loaded from JSON assets. */
  val playlistHolderList: StateFlow<List<PlaylistHolder>> = _playlistHolderList.asStateFlow()

  private val _uiState = MutableStateFlow(EffectUiState())
  /** A [StateFlow] emitting the current UI state representing effect controls and settings. */
  val uiState: StateFlow<EffectUiState> = _uiState.asStateFlow()

  /**
   * The [ExoPlayer] instance used for media playback, initialized lazily and automatically released
   * when this ViewModel is cleared.
   */
  val exoPlayer: ExoPlayer by lazy {
    ExoPlayer.Builder(application).build().apply { playWhenReady = true }
  }

  private var lottieOverlayOptions: Map<String, Effect> = emptyMap()

  init {
    loadPlaylists()
    loadLottieEffects()
  }

  private fun loadPlaylists() {
    viewModelScope.launch {
      try {
        val playlists =
          withContext(Dispatchers.IO) {
            loadPlaylistsFromJson(JSON_FILENAME, getApplication(), "EffectViewModel")
          }
        _playlistHolderList.value = playlists
        if (playlists.isNotEmpty()) {
          selectMediaItems(playlists.first().mediaItems)
        } else {
          _uiState.update {
            it.copy(
              errorMessage =
                getApplication<Application>().getString(R.string.no_loaded_playlists_error)
            )
          }
        }
      } catch (e: Exception) {
        _uiState.update {
          it.copy(
            errorMessage =
              getApplication<Application>()
                .getString(R.string.playlist_loading_error, JSON_FILENAME, e)
          )
        }
      }
    }
  }

  private fun loadLottieEffects() {
    viewModelScope.launch {
      val options =
        withContext(Dispatchers.IO) {
          buildMap {
            LottieEffectFactory.buildAvailableEffects(getApplication()).forEach { (name, effect) ->
              put(name, effect)
            }
          }
        }
      lottieOverlayOptions = options
      _uiState.update {
        it.copy(
          lottieOverlayOptions = ImmutableList.copyOf(options.keys),
          lottieOverlayName =
            getApplication<Application>().getString(R.string.lottie_effect_name_counter),
          lottieEffectsLoaded = true,
        )
      }
    }
  }

  /**
   * Updates the player with a new list of [MediaItem]s to play, clearing any active video effects
   * and enabling effect controls in the UI.
   *
   * @param mediaItems The list of media items to play.
   */
  fun selectMediaItems(mediaItems: List<MediaItem>) {
    exoPlayer.apply {
      setMediaItems(mediaItems)
      setVideoEffects(emptyList())
      prepare()
    }
    _uiState.update { it.copy(effectsEnabled = true, effectsChanged = false) }
  }

  /**
   * Toggles whether the contrast effect is enabled in the UI controls.
   *
   * @param checked Whether the contrast effect checkbox is checked.
   */
  fun updateContrastChecked(checked: Boolean) {
    _uiState.update {
      val value = if (checked) it.contrastValue else 0f
      it.copy(contrastChecked = checked, contrastValue = value, effectsChanged = true)
    }
  }

  /**
   * Updates the contrast effect value in the UI controls.
   *
   * @param value The contrast value in the range of -1f to 1f.
   */
  fun updateContrast(value: Float) {
    _uiState.update { it.copy(contrastValue = value, effectsChanged = true) }
  }

  /**
   * Toggles whether the confetti overlay is enabled in the UI controls.
   *
   * @param checked Whether the confetti overlay checkbox is checked.
   */
  fun updateConfetti(checked: Boolean) {
    _uiState.update { it.copy(confettiOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Toggles whether the clock overlay is enabled in the UI controls.
   *
   * @param checked Whether the clock overlay checkbox is checked.
   */
  fun updateClock(checked: Boolean) {
    _uiState.update { it.copy(clockOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Toggles whether the Lottie animation overlay is enabled in the UI controls.
   *
   * @param checked Whether the Lottie overlay checkbox is checked.
   */
  fun updateLottieChecked(checked: Boolean) {
    _uiState.update { it.copy(lottieOverlayChecked = checked, effectsChanged = true) }
  }

  /**
   * Updates the selected Lottie asset name in the UI controls.
   *
   * @param name The display name of the Lottie effect to apply.
   */
  fun updateLottieName(name: String) {
    _uiState.update { it.copy(lottieOverlayName = name, effectsChanged = true) }
  }

  /**
   * Toggles whether the custom text overlay is enabled in the UI controls.
   *
   * @param checked Whether the custom text overlay checkbox is checked.
   */
  fun updateTextChecked(checked: Boolean) {
    _uiState.update {
      val effectsChanged = !checked // Replicating original logic
      it.copy(textOverlayChecked = checked, effectsChanged = effectsChanged)
    }
  }

  /**
   * Updates the text content for the custom text overlay in the UI controls.
   *
   * @param text The text string to display, or null if empty.
   */
  fun updateText(text: String?) {
    _uiState.update { it.copy(textOverlayText = text, effectsChanged = true) }
  }

  /**
   * Updates the font color for the custom text overlay in the UI controls.
   *
   * @param color The [Color] to apply to the overlay text.
   */
  fun updateTextColor(color: Color) {
    _uiState.update {
      it.copy(textOverlayColor = color, effectsChanged = it.textOverlayText != null)
    }
  }

  /**
   * Updates the alpha scale (transparency) for the custom text overlay in the UI controls.
   *
   * @param alpha The alpha scale from 0f (transparent) to 1f (opaque).
   */
  fun updateTextAlpha(alpha: Float) {
    _uiState.update {
      it.copy(textOverlayAlpha = alpha, effectsChanged = it.textOverlayText != null)
    }
  }

  /**
   * Builds the video effects list based on the current [EffectUiState] and applies them to the
   * underlying [ExoPlayer].
   */
  fun applyEffects() {
    val currentState = _uiState.value
    val listBuilder = ImmutableList.builder<Effect>()

    if (currentState.contrastChecked && currentState.contrastValue != 0f) {
      listBuilder.add(Contrast(currentState.contrastValue))
    }

    val overlaysBuilder = ImmutableList.builder<TextureOverlay>()
    if (currentState.confettiOverlayChecked) {
      overlaysBuilder.add(ConfettiOverlay())
    }

    if (currentState.clockOverlayChecked) {
      overlaysBuilder.add(ClockOverlay())
    }

    if (currentState.lottieOverlayChecked) {
      val lottieEffect = lottieOverlayOptions[currentState.lottieOverlayName]
      lottieEffect?.let { listBuilder.add(it) }
    }

    val textOverlayText = currentState.textOverlayText
    if (currentState.textOverlayChecked && textOverlayText != null) {
      val spannableOverlayText = SpannableString(textOverlayText)
      spannableOverlayText.setSpan(
        ForegroundColorSpan(currentState.textOverlayColor.toArgb()),
        /* start= */ 0,
        textOverlayText.length,
        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE,
      )
      val staticOverlaySettings =
        StaticOverlaySettings.Builder().setAlphaScale(currentState.textOverlayAlpha).build()
      overlaysBuilder.add(
        TextOverlay.createStaticTextOverlay(spannableOverlayText, staticOverlaySettings)
      )
    }
    val overlays = overlaysBuilder.build()
    if (overlays.isNotEmpty()) {
      listBuilder.add(OverlayEffect(overlays))
    }

    exoPlayer.apply {
      setVideoEffects(listBuilder.build())
      prepare()
    }
    _uiState.update { it.copy(effectsChanged = false) }
  }

  /** Clears any active error message in the [EffectUiState]. */
  fun clearErrorMessage() {
    _uiState.update { it.copy(errorMessage = null) }
  }

  override fun onCleared() {
    super.onCleared()
    exoPlayer.release()
  }

  private companion object {
    private const val JSON_FILENAME = "media.playlist.json"
  }
}
