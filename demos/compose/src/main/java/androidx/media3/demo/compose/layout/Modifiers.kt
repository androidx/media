package androidx.media3.demo.compose.layout

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale

@Composable
internal fun Modifier.noRippleClickable(onClick: () -> Unit): Modifier {
  return then(
    clickable(
      interactionSource = remember { MutableInteractionSource() },
      indication = null, // to prevent the ripple from the tap
    ) {
      onClick()
    }
  )
}

@Composable
internal fun Modifier.scaledWithAspectRatio(
  contentScale: ContentScale,
  videoAspectRatio: Float,
): Modifier {
  if (videoAspectRatio == 0f) {
    // Video has not been decoded yet, let the component occupy incoming constraints
    return Modifier
  }
  // TODO: decide if using aspectRatio is better than layout out the Composables ourselves
  //   ideally we would have aspectRatio( () -> videoAspectRatio ) to defer reads
  return when (contentScale) {
    ContentScale.FillWidth ->
      then(
        Modifier.fillMaxWidth().aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = false)
      )
    ContentScale.FillHeight ->
      then(
        Modifier.fillMaxHeight().aspectRatio(videoAspectRatio, matchHeightConstraintsFirst = true)
      )
    ContentScale.Fit -> then(Modifier.aspectRatio(videoAspectRatio))
    // TODO: figure out how to implement these
    //        ContentScale.Crop -> ...? // like RESIZE_MODE_ZOOM? How to measure "deformation"?
    //        ContentScale.Inside -> ...? // no resizeMode equivalent, need to test with a tiny vid
    //        ContentScale.FillBounds -> then(Modifier)? like None? or is None like Inside?
    ContentScale.None -> then(Modifier)
    else -> then(Modifier)
  }
}