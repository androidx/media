# ExoPlayer IMA module

The ExoPlayer IMA module provides an [AdsLoader][] implementation wrapping the
[Interactive Media Ads SDK for Android][IMA]. You can use it to insert ads into
content played using ExoPlayer.

[IMA]: https://developers.google.com/interactive-media-ads/docs/sdks/android/
[AdsLoader]: ../exoplayer/src/main/java/androidx/media3/exoplayer/source/ads/AdsLoader.java

## Getting the module

The easiest way to get the module is to add it as a gradle dependency:

```gradle
implementation 'androidx.media3:media3-exoplayer-ima:1.X.X'
```

where `1.X.X` is the version, which must match the version of the other media
modules being used.

Alternatively, you can clone this GitHub project and depend on the module
locally. Instructions for doing this can be found in the [top level README][].

[top level README]: ../../README.md

## Using the module

To use the module, follow the instructions on the
[Ad insertion page](https://developer.android.com/media/media3/exoplayer/ad-insertion#declarative-ad-support)
of the developer guide. The `AdsLoaderProvider` passed to the player's
`DefaultMediaSourceFactory` should return an `ImaAdsLoader`. Note that the IMA
module only supports players that are accessed on the application's main thread.

Resuming the player after entering the background requires some special
handling when playing ads. The player and its media source are released on
entering the background, and are recreated when returning to the foreground.
When playing ads it is necessary to persist ad playback state while in the
background by keeping a reference to the `ImaAdsLoader`. When re-entering the
foreground, pass the same instance back when
`AdsLoaderProvider.getAdsLoader(MediaItem.AdsConfiguration adsConfiguration)`
is called to restore the state. It is also important to persist the player
position when entering the background by storing the value of
`player.getContentPosition()`.  On returning to the foreground, seek to that
position before preparing the new player instance. Finally, it is important to
call `ImaAdsLoader.release()` when playback has finished and will not be
resumed.

You can try the IMA module in the ExoPlayer demo app, which has test content in
the "IMA sample ad tags" section of the sample chooser. The demo app's
`PlayerActivity` also shows how to persist the `ImaAdsLoader` instance and the
player position when backgrounded during ad playback.

## Links

*   [Developer Guide][]
*   [Javadoc][]
*   [Supported platforms][]

[Developer Guide]: https://developer.android.com/media/media3/exoplayer/ad-insertion
[Javadoc]: https://developer.android.com/reference/androidx/media3/exoplayer/ima/package-summary
[Supported platforms]: https://developers.google.com/interactive-media-ads/docs/sdks/other
