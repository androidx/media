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

import android.net.Uri
import androidx.media3.datasource.DataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.media3.test.utils.HttpDataSourceTestEnv
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import okhttp3.Call
import okhttp3.OkHttpClient
import org.junit.Rule
import org.junit.runner.RunWith

/** [DataSource] contract tests for [OkHttpDataSource].  */
@RunWith(AndroidJUnit4::class)
class OkHttpDataSourceContractTest : DataSourceContractTest() {
    @get:Rule
    var httpDataSourceTestEnv = HttpDataSourceTestEnv()

    override fun createDataSource(): DataSource {
        val okHttpClient: Call.Factory = OkHttpClient.Builder()
                .build()

        return OkHttpDataSource.Factory(okHttpClient).createDataSource()
    }

    override fun getTestResources(): ImmutableList<TestResource> {
        return httpDataSourceTestEnv.servedResources
    }

    override fun getNotFoundUri(): Uri {
        return Uri.parse(httpDataSourceTestEnv.nonexistentUrl)
    }
}