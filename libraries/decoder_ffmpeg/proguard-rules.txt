# Proguard rules specific to the FFmpeg extension.

# This prevents the names of native methods from being obfuscated.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}

# This method is called from native code
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
  private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}
