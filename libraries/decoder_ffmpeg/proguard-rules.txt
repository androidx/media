# Proguard rules specific to the FFmpeg extension.

# This prevents the names of native methods from being obfuscated. The class names are
# kept as well (a -keep rule keeps the matched classes), which the JNI layer relies on:
# FfmpegLibrary, FfmpegAudioDecoder and ExperimentalFfmpegVideoDecoder are all found by
# name in JNI_OnLoad and their native methods are bound there via RegisterNatives, so
# neither the classes nor their native methods may be renamed or stripped by R8.
-keep,includedescriptorclasses class androidx.media3.decoder.ffmpeg.** {
    native <methods>;
}

# This method is called from native code
-keep, includedescriptorclasses class androidx.media3.decoder.ffmpeg.FfmpegAudioDecoder {
  private java.nio.ByteBuffer growOutputBuffer(androidx.media3.decoder.SimpleDecoderOutputBuffer, int);
}

# Fields and methods of this class are accessed by name from native code
# (GetFieldID/GetMethodID), so the class must not be renamed or stripped.
-keep class androidx.media3.decoder.VideoDecoderOutputBuffer {
  *;
}
