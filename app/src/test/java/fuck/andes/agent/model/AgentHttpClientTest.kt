package fuck.andes.agent.model

import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import okhttp3.ConnectionSpec
import okhttp3.TlsVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentHttpClientTest {
    @Test
    fun tlsFallbackKeepsOnlyTls12AndTls13() {
        val fallback = AgentHttpClient.client.connectionSpecs[1]

        assertTrue(fallback.isTls)
        assertEquals(
            listOf(TlsVersion.TLS_1_3, TlsVersion.TLS_1_2),
            fallback.tlsVersions,
        )
        assertTrue(
            fallback.cipherSuites.orEmpty().size >
                ConnectionSpec.MODERN_TLS.cipherSuites.orEmpty().size,
        )
    }

    @Test
    fun networkFailureAddsHostAndPreservesTlsCause() {
        val cause = SSLHandshakeException("SSLV3_ALERT_HANDSHAKE_FAILURE")
        val failure = AgentHttpClient.networkFailure(
            "https://anyrouter.top/v1/chat/completions",
            cause,
        )

        assertTrue(failure is IOException)
        assertTrue(failure.message.orEmpty().contains("anyrouter.top"))
        assertTrue(failure.message.orEmpty().contains("HTTPS/TLS"))
        assertTrue(failure.message.orEmpty().contains("SSLV3_ALERT_HANDSHAKE_FAILURE"))
        assertEquals(cause, failure.cause)
    }
}
