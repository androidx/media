/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.datasource.okhttp

import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource.HttpDataSourceException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.Closeable
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.logging.Handler
import java.util.logging.LogRecord

/** [DataSource] contract tests for [OkHttpDataSource].  */
@RunWith(AndroidJUnit4::class)
class OkHttpDataSourceCancellationTest {
    val logs: MutableList<String> = mutableListOf()

    val okHttpClient: Call.Factory = OkHttpClient.Builder()
            .build()

    private var logging: Closeable? = null

    @After
    fun disableLogging() {
        logging?.close()
    }

    fun createDataSource(): DataSource {
        return OkHttpDataSource.Factory(okHttpClient).createDataSource()
    }

    @Test
    fun handlesFastCancellations() {
        assumeInternet()

        val interruptPoint = CountDownLatch(1)
        val interruptedPoint = CountDownLatch(1)

        logging = OkHttpDebugLogging.enableHttp2(handler = TestLogHandler { message ->
            if (message.matches(">>.*HEADERS".toRegex())) {
                interruptPoint.countDown()
                interruptedPoint.awaitUninterruptible()
            }
        })

        val testThread = Thread.currentThread()

        Thread {
            interruptPoint.await()

            testThread.interrupt()

            interruptedPoint.countDown()
        }.start()

        val dataSource = createDataSource()
        val content = DataSpec.Builder()
                .setUri("https://storage.googleapis.com/exoplayer-test-media-1/gen-3/screens/dash-vod-single-segment/video-avc-baseline-480.mp4")
                .build()

        try {
            dataSource.open(content)
            fail()
        } catch (hdse: HttpDataSourceException) {
            // expected
        }

//        logs.forEach {
//            println("'$it'")
//        }

        // Check we started a request
        assertThat(logs).contains(">> 0x00000003   102 HEADERS       END_STREAM|END_HEADERS")

        // If execute is used in OkHttpDataSource.open() then this RST_STREAM is not sent.
        assertThat(logs).contains(">> 0x00000003     4 RST_STREAM    ")
    }

    private fun assumeInternet() {
        try {
            InetAddress.getByName("www.google.com")
        } catch (uhe: UnknownHostException) {
            assumeTrue("requires network", false)
        }
    }

    inner class TestLogHandler(val onMessage: (message: String) -> Unit) : Handler() {
        override fun publish(log: LogRecord) {
            val message = log.message

            logs.add(message)

            onMessage(message)
        }

        override fun flush() {
        }

        override fun close() {
        }
    }
}

private fun CountDownLatch.awaitUninterruptible() {
    var interrupted = false

    try {
        while (true) {
            try {
                await()
                return
            } catch (ie: InterruptedException) {
                interrupted = true
            }
        }
    } finally {
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
    }
}
