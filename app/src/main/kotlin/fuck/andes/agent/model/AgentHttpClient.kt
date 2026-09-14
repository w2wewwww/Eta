package fuck.andes.agent.model

import java.io.IOException
import java.net.URI
import javax.net.ssl.SSLException
import okhttp3.ConnectionSpec
import okhttp3.OkHttpClient
import okhttp3.TlsVersion
import java.util.concurrent.TimeUnit

/**
 * 模块全局 OkHttp 客户端。
 *
 * 由所有 Provider 实现共享，避免重复创建连接池。超时设置与历史
 * HttpURLConnection 配置保持一致。
 */
internal object AgentHttpClient {

    private const val CONNECT_TIMEOUT_MS = 15_000L
    private const val READ_TIMEOUT_MS = 60_000L
    private const val WRITE_TIMEOUT_MS = 30_000L

    /**
     * Some gateways reject Android's small modern cipher-suite ClientHello.
     * Keep the fallback limited to TLS 1.2/1.3; never re-enable obsolete TLS.
     */
    private val TLS_12_PLUS_FALLBACK = ConnectionSpec.Builder(ConnectionSpec.MODERN_TLS)
        .allEnabledCipherSuites()
        .tlsVersions(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2)
        .build()

    private val CONNECTION_SPECS = listOf(
        ConnectionSpec.MODERN_TLS,
        TLS_12_PLUS_FALLBACK,
        ConnectionSpec.CLEARTEXT,
    )

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .readTimeout(READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .connectionSpecs(CONNECTION_SPECS)
            .retryOnConnectionFailure(true)
            .build()
    }

    /** Adds endpoint context without hiding the original TLS/IO diagnostic. */
    fun networkFailure(url: String, throwable: IOException): IOException {
        val host = runCatching { URI(url).host }.getOrNull().orEmpty().ifBlank { url }
        val sslCause = generateSequence(throwable as Throwable?) { it.cause }
            .filterIsInstance<SSLException>()
            .firstOrNull()
        val category = if (sslCause != null) "HTTPS/TLS 握手或连接失败" else "网络请求失败"
        val detail = (sslCause ?: throwable).message
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { ": $it" }
            .orEmpty()
        return IOException("$category（$host）$detail", throwable)
    }
}
