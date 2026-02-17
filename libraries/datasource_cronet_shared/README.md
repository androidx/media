# Cronet DataSource shared module

This module contains the shared [Cronet][] [HttpDataSource][] implementation
used by both the [Cronet DataSource][] and [Cronet DataSource Embedded][]
modules.

This module should not be used directly. Instead, depend on one of the
following modules:

*   `media3-datasource-cronet`: Uses the Cronet implementation provided by
    Google Play Services.
*   `media3-datasource-cronet-embedded`: Bundles the Cronet implementation
    directly into your application.

[HttpDataSource]: ../datasource/src/main/java/androidx/media3/datasource/HttpDataSource.java
[Cronet]: https://developer.android.com/guide/topics/connectivity/cronet
[Cronet DataSource]: ../datasource_cronet
[Cronet DataSource Embedded]: ../datasource_cronet_embedded

## Links

*   [Javadoc][]

[Javadoc]: https://developer.android.com/reference/androidx/media3/datasource/cronet/package-summary
