# Cast module

This module provides a [Player][] implementation that controls a Cast receiver
app.

[Player]: ../common/src/main/java/androidx/media3/common/Player.html

## License note

Note that whilst the code in this repository is licensed under [Apache 2.0][],
using this module requires depending on the Google Cast SDK
(`com.google.android.gms:play-services-cast-framework`), which is part of Google
Play services, is licensed separately under the
[Android Software Development Kit License Agreement][] and is subject to the
[Google Cast SDK Additional Developer Terms of Service][].

[Apache 2.0]: ../../LICENSE
[Android Software Development Kit License Agreement]: https://developer.android.com/studio/terms
[Google Cast SDK Additional Developer Terms of Service]: https://developers.google.com/cast/docs/terms

## Getting the module

The easiest way to get the module is to add it as a gradle dependency:

```kotlin
implementation("androidx.media3:media3-cast:1.X.X")
```

Or in Gradle Groovy DSL `build.gradle`:

```groovy
implementation 'androidx.media3:media3-cast:1.X.X'
```

where `1.X.X` is the version, which must match the version of the other media
modules being used.

Alternatively, you can clone this GitHub project and depend on the module
locally. Instructions for doing this can be found in the [top level README][].

[top level README]: ../../README.md

## Using the module

Create a `CastPlayer` and use it to control a Cast receiver app. Since
`CastPlayer` implements the `Player` interface, it can be passed to all media
components that accept a `Player`, including the UI components provided by the
UI module.

## Links

*   [Javadoc][]

[Javadoc]: https://developer.android.com/reference/androidx/media3/cast/package-summary
