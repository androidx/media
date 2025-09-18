package androidx.media3.demo.shortform.lazycolumn

import android.annotation.SuppressLint
import android.media.MediaDataSource
import java.io.ByteArrayOutputStream
import java.io.FileInputStream
import java.io.SequenceInputStream
import java.util.Enumeration
import kotlin.math.min

@SuppressLint("NewApi")
class ConcatMediaDataSource(ids: List<String?>) : MediaDataSource() {

    private var data: ByteArray? = null
    private val mergedInputStream: SequenceInputStream by lazy { getInputStream(ids) }

    private fun ensureDataBuffered() {
        synchronized(this) {
            if (data != null) return
            ByteArrayOutputStream().use { buffer ->
                mergedInputStream.use { input ->
                    input.copyTo(buffer)
                }
                data = buffer.toByteArray()
            }
        }
    }

    override fun readAt(
        position: Long,
        buffer: ByteArray,
        offset: Int,
        size: Int,
    ): Int {
        synchronized(this) {
            ensureDataBuffered()
            data?.let {
                if (position > it.size) return -1
                val actualSize = min(size, it.size - position.toInt())
                System.arraycopy(it, position.toInt(), buffer, offset, actualSize)
                return actualSize
            } ?: run {
                return -1
            }
        }
    }

    override fun getSize(): Long {
        synchronized(this) {
            return (data?.size?.toLong() ?: -1)
        }
    }

    override fun close() {
        synchronized(this) {
            mergedInputStream.close()
            data = null
        }
    }

    private fun getInputStream(files: List<String?>): SequenceInputStream {
        val inputStreams = files.map { FileInputStream(it) }
        val allInputStreams = listOf(*inputStreams.toTypedArray())
        return SequenceInputStream(allInputStreams.iterator().asEnumeration())
    }
}

fun <T> Iterator<T>.asEnumeration(): Enumeration<T> {
    return object : Enumeration<T> {
        override fun hasMoreElements(): Boolean = this@asEnumeration.hasNext()

        override fun nextElement(): T = this@asEnumeration.next()
    }
}
