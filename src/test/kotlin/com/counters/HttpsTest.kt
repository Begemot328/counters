package com.counters

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.network.tls.certificates.buildKeyStore
import io.ktor.network.tls.certificates.saveToFile
import io.ktor.server.engine.CommandLineConfig
import io.ktor.server.engine.ConnectorType
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.net.ssl.SSLException
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * Boots the real server the way `./gradlew run` does — EngineMain's construction over application.conf — with
 * only the port (random) and the keystore (generated here) overridden, and talks to it over a
 * real socket. This is the only test that exercises TLS: `testApplication` never opens one.
 */
class HttpsTest {

    // Block body on purpose: `= runBlocking { … }` would give the method a non-Unit return type,
    // and JUnit 5 silently skips non-void test methods.
    @Test
    fun `serves HTTPS only, with the configured certificate`() = runBlocking<Unit> {
        val keyStore = buildKeyStore {
            certificate(ALIAS) {
                password = PASSWORD
                domains = listOf("localhost")
                keySizeInBits = 2048
            }
        }
        val keyStoreFile = Files.createTempFile("counters-test-", ".p12").toFile().apply {
            deleteOnExit()
            keyStore.saveToFile(this, PASSWORD)
        }

        // Same construction as EngineMain.main(): application.conf from the classpath, with
        // -P overrides for the port, the keystore and the test database.
        val commandLine = CommandLineConfig(
            arrayOf(
                "-P:ktor.deployment.sslPort=0",
                "-P:ktor.security.ssl.keyStore=${keyStoreFile.absolutePath}",
                "-P:ktor.security.ssl.keyAlias=$ALIAS",
                "-P:ktor.security.ssl.keyStorePassword=$PASSWORD",
                "-P:ktor.security.ssl.privateKeyPassword=$PASSWORD",
            ) + TestDatabase.commandLineOverrides(),
        )
        val server = EmbeddedServer(commandLine.rootConfig, Netty) { takeFrom(commandLine.engineConfig) }
        server.start(wait = false)
        try {
            val connectors = server.engine.resolvedConnectors()
            assertEquals(listOf(ConnectorType.HTTPS), connectors.map { it.type }, "no plaintext connector is opened")
            val port = connectors.single().port

            HttpClient(CIO) { engine { https { trustManager = trustManagerFor(keyStore) } } }.use { trusting ->
                assertEquals(HttpStatusCode.OK, trusting.get("https://localhost:$port/counters").status)
            }

            HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 5_000 } }.use { defaultTrust ->
                val failure = assertFails { defaultTrust.get("https://localhost:$port/counters") }
                // Depending on the engine the handshake failure surfaces as an SSLException or as
                // the underlying certificate-path failure (a GeneralSecurityException).
                assertTrue(
                    generateSequence<Throwable>(failure) { it.cause }
                        .any { it is SSLException || it is GeneralSecurityException },
                    "a client without our certificate must fail the TLS handshake, got: $failure",
                )
            }

            HttpClient(CIO) { install(HttpTimeout) { requestTimeoutMillis = 5_000 } }.use { plaintext ->
                assertFails("plaintext HTTP on the TLS port must be rejected") {
                    plaintext.get("http://localhost:$port/counters")
                }
            }
        } finally {
            server.stop(gracePeriodMillis = 500, timeoutMillis = 2000)
        }
    }

    private fun trustManagerFor(keyStore: KeyStore): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers
            .filterIsInstance<X509TrustManager>()
            .single()

    private companion object {
        const val ALIAS = "counters"
        const val PASSWORD = "changeit"
    }
}
