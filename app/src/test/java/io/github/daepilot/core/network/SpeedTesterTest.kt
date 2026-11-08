package io.github.daepilot.core.network

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SpeedTesterTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun tester(downloadBytes: Int = 128 * 1024, uploadBytes: Int = 64 * 1024) =
        SpeedTester(
            baseUrl = server.url("/").toString().trimEnd('/'),
            downloadBytes = downloadBytes,
            uploadBytes = uploadBytes,
        )

    @Test
    fun `reports download and upload throughput`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.path?.startsWith("/__down") == true ->
                    MockResponse().setBody(Buffer().write(ByteArray(128 * 1024)))
                request.path?.startsWith("/__up") == true ->
                    MockResponse().setResponseCode(200)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val result = tester().run()
        assertEquals(128L * 1024, result.downloadBytes)
        assertEquals(64L * 1024, result.uploadBytes)
        assertNotNull(result.downloadMbps)
        assertNotNull(result.uploadMbps)
        assertNull(result.error)
    }

    @Test
    fun `server errors produce an error result`() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(500)
        }
        val result = tester().run()
        assertNull(result.downloadMbps)
        assertNull(result.uploadMbps)
        assertNotNull(result.error)
    }
}
