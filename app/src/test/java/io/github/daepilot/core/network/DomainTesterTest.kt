package io.github.daepilot.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InterruptedIOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException

class DomainTesterTest {
    private lateinit var server: MockWebServer
    private lateinit var tester: DomainTester

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        tester = DomainTester(
            client = DomainTester.defaultClient(),
            targets = emptyList(),
            exitProbeUrl = server.url("/trace").toString(),
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `204 response is reachable`() {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = runBlocking { tester.probe(target()) }
        assertTrue(result.reachable)
        assertEquals("HTTP 204", result.detail)
    }

    @Test
    fun `server error is reported with its code`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val result = runBlocking { tester.probe(target()) }
        assertFalse(result.reachable)
        assertEquals("HTTP 500", result.detail)
    }

    @Test
    fun `google target requires the 204 endpoint`() {
        server.enqueue(MockResponse().setResponseCode(200))
        val result = runBlocking { tester.probe(target(expect204 = true)) }
        assertFalse(result.reachable)
        assertEquals("HTTP 200", result.detail)
    }

    @Test
    fun `head rejection falls back to get`() {
        server.enqueue(MockResponse().setResponseCode(405))
        server.enqueue(MockResponse().setResponseCode(204))
        val result = runBlocking { tester.probe(target(expect204 = true)) }
        assertTrue(result.reachable)
    }

    @Test
    fun `failure reasons are human readable`() {
        assertEquals("DNS 解析失败", tester.classify(UnknownHostException("x")))
        assertEquals("连接超时", tester.classify(SocketTimeoutException()))
        assertEquals("连接超时", tester.classify(InterruptedIOException("timeout")))
        assertEquals("TLS 握手失败", tester.classify(SSLHandshakeException("x")))
    }

    @Test
    fun `cloudflare trace is parsed`() {
        val exit = tester.parseExit("ip=1.2.3.4\nloc=CN\ncolo=LAX\nwarp=off\n")
        assertEquals("1.2.3.4", exit?.ip)
        assertEquals("CN", exit?.country)
        assertEquals("LAX", exit?.colo)
        assertNull(tester.parseExit("not a trace"))
    }

    private fun target(expect204: Boolean = false): DomainTarget =
        DomainTarget(
            label = "Test",
            host = "localhost",
            url = server.url("/probe").toString(),
            expect204 = expect204,
        )
}
