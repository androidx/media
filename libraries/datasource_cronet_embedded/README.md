# Cronet DataSource Embedded module

This module provides an [HttpDataSource][] implementation that uses [Cronet][]
bundled directly into your application.

Cronet is the Chromium network stack made available to Android apps as a
library. It takes advantage of multiple technologies that reduce the latency and
increase the throughput of the network requests that your app needs to work. It
natively supports the HTTP, HTTP/2, and HTTP/3 over QUIC protocols. Cronet is
used by some of the world's biggest streaming applications, including YouTube,
and is our recommended network stack for most use cases.

[HttpDataSource]: ../datasource/src/main/java/androidx/media3/datasource/HttpDataSource.java
[Cronet]: https://developer.android.com/guide/topics/connectivity/cronet

## Getting the module

The easiest way to get the module is to add it as a gradle dependency:

```groovy
implementation 'androidx.media3:media3-datasource-cronet-embedded:1.X.X'
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

If your application only needs to play http(s) content, using the Cronet
extension is as simple as updating `DataSource.Factory` instantiations in your
application code to use `CronetDataSource.Factory`. If your application also
needs to play non-http(s) content such as local files, use:

```
new DefaultDataSource.Factory(
    ...
    /* baseDataSourceFactory= */ new CronetDataSource.Factory(...) );
```

## Cronet Embedded

This module bundles a full Cronet implementation directly into your application.
Cronet Embedded adds approximately 4MB to your application for each architecture, and so we do not
recommend it for most use cases. That said, use of Cronet Embedded may be
appropriate if:

* A large percentage of your users are in markets where Google Play Services is
  not widely available.
* You want to control the exact version of the Cronet implementation being used.

If application size is a concern and your users have Google Play Services
available, consider using the [Cronet DataSource][] module instead, which loads
Cronet from Google Play Services with negligible size impact.

[Cronet DataSource]: ../datasource_cronet

### CronetEngine instantiation

Cronet's [Send a simple request][] page documents the simplest way of building a
`CronetEngine`. Since this module bundles Cronet directly, you can build a
`CronetEngine` without needing Google Play Services:

```
CronetEngine cronetEngine = new CronetEngine.Builder(context).build();
```

[Send a simple request]: https://developer.android.com/guide/topics/connectivity/cronet/start

## Links

*   [Javadoc][]

[Javadoc]: https://developer.android.com/reference/androidx/media3/datasource/cronet/package-summary
