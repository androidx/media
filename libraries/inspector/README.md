# Inspector module

Provides functionality for inspecting media files, including retrieving metadata
and providing a replacement for `android.media.MediaExtractor`.

## Getting the module

The easiest way to get the module is to add it as a gradle dependency:

```kotlin
implementation("androidx.media3:media3-inspector:1.X.X")
```

Or in Gradle Groovy DSL `build.gradle`:

```groovy
implementation 'androidx.media3:media3-inspector:1.X.X'
```

where `1.X.X` is the version, which must match the version of the other media
modules being used.

Alternatively, you can clone this GitHub project and depend on the module
locally. Instructions for doing this can be found in the [top level README][].

[top level README]: ../../README.md

## Links

*   [Javadoc][]

[Javadoc]: https://developer.android.com/reference/androidx/media3/inspector/package-summary
