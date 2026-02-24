package androidx.media3.datasource.ktor

import androidx.media3.datasource.DataSource
import androidx.media3.test.utils.DataSourceContractTest
import androidx.media3.test.utils.HttpDataSourceTestEnv
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.ImmutableList
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Rule
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class KtorDataSourceContractTest : DataSourceContractTest() {

    @JvmField
    @Rule
    var httpDataSourceTestEnv = HttpDataSourceTestEnv()
    val httpClient = HttpClient() {
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
