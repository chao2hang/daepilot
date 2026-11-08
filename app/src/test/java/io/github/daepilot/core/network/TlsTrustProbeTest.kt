package io.github.daepilot.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/**
 * The certificate decision the app acts on comes from a real handshake: a
 * self-signed node certificate must be reported as untrusted so the per-node
 * override is applied, while a port that is not TLS (or is dead) must stay
 * inconclusive so a broken node is never marked as insecure.
 */
class TlsTrustProbeTest {
    private fun keytool(vararg args: String) {
        val binary = File(System.getProperty("java.home"), "bin/keytool")
        val path = if (binary.exists()) binary.absolutePath else "$binary.exe"
        val process = ProcessBuilder(listOf(path) + args.toList())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val finished = process.waitFor(60, TimeUnit.SECONDS)
        check(finished && process.exitValue() == 0) { "keytool failed: $output" }
    }

    private fun selfSignedServer(): SSLServerSocket {
        val store = KeyStore.getInstance("PKCS12")
        store.load(null, null)
        val file = Files.createTempFile("daepilot-probe", ".p12").toFile()
        file.delete()
        // keytool writes the self-signed certificate itself: no internal JDK API.
        keytool(
            "-genkeypair", "-alias", "probe", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=self-signed.test",
            "-storetype", "PKCS12", "-storepass", "changeit",
            "-keystore", file.absolutePath,
        )
        file.inputStream().use { store.load(it, "changeit".toCharArray()) }
        file.delete()
        val keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        keys.init(store, "changeit".toCharArray())
        val context = SSLContext.getInstance("TLS")
        context.init(keys.keyManagers, null, null)
        return context.serverSocketFactory.createServerSocket(0) as SSLServerSocket
    }

    @Test
    fun selfSignedCertificateIsDetectedAsUntrusted() {
        selfSignedServer().use { server ->
            val handshakes = Thread {
                runCatching {
                    (server.accept() as SSLSocket).use { it.startHandshake() }
                }
            }
            handshakes.start()
            val verdict = TlsTrustProbe.probe(
                "127.0.0.1", server.localPort, "self-signed.test", timeoutMs = 8000,
            )
            assertEquals(false, verdict)
            handshakes.join(5_000)
        }
    }

    @Test
    fun plainTextPortIsInconclusiveRatherThanUntrusted() {
        ServerSocket().use { server ->
            val acceptor = Thread { runCatching { server.accept()?.close() } }
            acceptor.start()
            val verdict = TlsTrustProbe.probe(
                "127.0.0.1", server.localPort, "plain.test", timeoutMs = 5000,
            )
            assertNull(verdict)
            acceptor.join(5_000)
        }
    }

    @Test
    fun closedPortIsInconclusive() {
        val port = ServerSocket(0).use { it.localPort }
        assertNull(TlsTrustProbe.probe("127.0.0.1", port, "dead.test", timeoutMs = 2000))
    }

    @Test
    fun invalidEndpointIsRejectedBeforeAnyConnection() {
        assertNull(TlsTrustProbe.probe("", 443, "x", timeoutMs = 100))
        assertNull(TlsTrustProbe.probe("node.example", 0, "x", timeoutMs = 100))
    }
}
