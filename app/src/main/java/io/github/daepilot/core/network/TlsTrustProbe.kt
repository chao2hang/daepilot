package io.github.daepilot.core.network

import java.net.InetSocketAddress
import java.net.Socket
import java.security.cert.CertificateException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/**
 * Checks a node's certificate the same way dae does: a TLS handshake with the
 * link's SNI against the platform trust store.
 *
 * `false` means the server answered but its certificate cannot be verified
 * (self-signed, unknown CA or name mismatch) — exactly the case that makes dae
 * log `x509: certificate signed by unknown authority` and mark a working node
 * as failed. `null` means the probe could not reach a conclusion (dead port,
 * timeout, not TLS), so the node's trust setting must be left untouched.
 */
object TlsTrustProbe {
    fun probe(host: String, port: Int, serverName: String, timeoutMs: Int = 2500): Boolean? {
        if (host.isBlank() || port !in 1..65535) return null
        return try {
            val raw = Socket().apply {
                connect(InetSocketAddress(host, port), timeoutMs)
                soTimeout = timeoutMs
            }
            val socket = SSLContext.getInstance("TLS").apply { init(null, null, null) }
                .socketFactory
                .createSocket(raw, serverName.ifBlank { host }, port, true) as SSLSocket
            socket.use {
                it.sslParameters = it.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                it.startHandshake()
                true
            }
        } catch (e: SSLHandshakeException) {
            val certFailure = e.cause is CertificateException ||
                e.message?.contains("certificate", ignoreCase = true) == true
            if (certFailure) false else null
        } catch (_: Exception) {
            null
        }
    }
}
