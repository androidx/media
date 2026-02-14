# Ktor DataSource module

This module provides an [HttpDataSource][] implementation that uses [Ktor][].

Ktor is a multiplatform HTTP client developed by JetBrains. It supports HTTP/2,
WebSocket, and Kotlin coroutines for asynchronous operations.

[HttpDataSource]: ../datasource/src/main/java/androidx/media3/datasource/HttpDataSource.java
[Ktor]: https://ktor.io/

## Getting the module

The easiest way to get the module is to add it as a gradle dependency:

```groovy
implementation 'androidx.media3:media3-datasource-ktor:1.X.X'
```

where `1.X.X` is the version, which must match the version of the other media
modules being used.

Alternatively, you can clone this GitHub project and depend on the module
locally. Instructions for doing this can be found in the [top level README][].

[top level README]: ../../README.md

## Using the module

Media components request data through `DataSource` instances. These instances
are obtained from instances of `DataSource.Factory`, which are instantiated and
injected from application code.

If your application only needs to play http(s) content, using the Ktor
extension is as simple as updating any `DataSource.Factory` instantiations in
your application code to use `KtorDataSource.Factory`. If your application
also needs to play non-http(s) content such as local files, use:
```
new DefaultDataSourceFactory(
    ...
    /* baseDataSourceFactory= */ new KtorDataSource.Factory(...));
```

### Using with OkHttp engine

```kotlin
val dataSourceFactory = KtorDataSource.Factory(OkHttp.create())
```

### Using with a custom HttpClient

```kotlin
val httpClient = HttpClient(OkHttp) {
    engine {
        // Configure OkHttp engine
    }
}
val dataSourceFactory = KtorDataSource.Factory(httpClient)
```

## Links

*   [Javadoc][]

[Javadoc]: https://developer.android.com/reference/androidx/media3/datasource/ktor/package-summary
