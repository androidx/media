/*
 * Copyright 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package androidx.media3.datasource.ktor

import androidx.media3.datasource.DataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.media3.test.utils.HttpDataSourceTestEnv
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KtorDataSourceContractTest : DataSourceContractTest() {

  @JvmField @Rule var httpDataSourceTestEnv = HttpDataSourceTestEnv()
  val httpClient =
    HttpClient() {
      install(HttpTimeout) {
        requestTimeoutMillis = 400
        connectTimeoutMillis = 400
        socketTimeoutMillis = 400
      }
    }

  private val coroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun createDataSource(): DataSource {
    return KtorDataSource.Factory(httpClient, coroutineScope).createDataSource()
  }

  override fun getTestResources(): ImmutableList<TestResource> {
    return httpDataSourceTestEnv.servedResources
  }

  override fun getNotFoundResources(): MutableList<TestResource> {
    return httpDataSourceTestEnv.notFoundResources
  }
}
