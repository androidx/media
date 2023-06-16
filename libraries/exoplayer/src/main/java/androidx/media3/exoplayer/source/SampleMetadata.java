package androidx.media3.exoplayer.source;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.extractor.TrackOutput;

/* package */ final class SampleMetadata {
  final long timeUs;

  @C.BufferFlags
  final int flags;

  final int size;

  final long absoluteOffset;

  @Nullable
  final TrackOutput.CryptoData cryptoData;

  SampleMetadata(long timeUs, @C.BufferFlags int flags, int size, long absoluteOffset, @Nullable TrackOutput.CryptoData cryptoData) {
    this.timeUs = timeUs;
    this.flags = flags;
    this.size = size;
    this.absoluteOffset = absoluteOffset;
    this.cryptoData = cryptoData;
  }
}
