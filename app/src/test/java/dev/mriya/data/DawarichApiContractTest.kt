package dev.mriya.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.SerializationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class DawarichApiContractTest {

    data class RecordedRequest(val path: String, val query: String?, val authHeader: String?)
    data class StubResponse(val code: Int, val headers: Map<String, String> = emptyMap(), val body: String = "")

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private lateinit var baseUrl: String
    private val seenRequests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile
    private var handler: (RecordedRequest) -> StubResponse = { StubResponse(404) }

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        worker = thread(isDaemon = true, name = "dawarich-test-http") {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                sock.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val requestLine = reader.readLine() ?: return@use
                    val target = requestLine.split(' ').getOrNull(1) ?: "/"
                    val headers = HashMap<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                    val uri = URI.create("http://localhost$target")
                    val req = RecordedRequest(uri.path, uri.rawQuery, headers["authorization"])
                    seenRequests += req
                    val resp = handler(req)
                    val bodyBytes = resp.body.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    val head = buildString {
                        append("HTTP/1.1 ${resp.code} TEST\r\n")
                        append("Content-Length: ${bodyBytes.size}\r\n")
                        append("Connection: close\r\n")
                        resp.headers.forEach { (k, v) -> append("$k: $v\r\n") }
                        append("\r\n")
                    }
                    out.write(head.toByteArray(Charsets.US_ASCII))
                    if (bodyBytes.isNotEmpty()) out.write(bodyBytes)
                    out.flush()
                }
            }
        }
    }

    @After
    fun tearDown() {
        server.close()
        worker.join(1000)
    }

    @Test
    fun `points uses Bearer header and respects X-Total-Pages without leaking api_key in query`() = runBlocking {
        handler = { req ->
            val q = req.query.orEmpty().split('&')
            val body = if ("page=1" in q) {
                """[{"id":1,"latitude":50.45,"longitude":30.52,"timestamp":1700000000,"country":"Ukraine"}]"""
            } else {
                """[{"id":2,"latitude":52.23,"longitude":21.01,"timestamp":1700003600,"country":"Poland"}]"""
            }
            StubResponse(200, mapOf("X-Total-Pages" to "2", "Content-Type" to "application/json"), body)
        }

        val api = DawarichApi(baseUrl, "secret-token")
        val p1 = api.points(startAt = 1699999000L, page = 1)
        val p2 = api.points(startAt = 1699999000L, page = 2)

        assertEquals(2, p1.totalPages)
        assertEquals(1, p1.points.size)
        assertEquals("Ukraine", p1.points.first().country)
        assertEquals("Poland", p2.points.first().country)

        assertEquals(2, seenRequests.size)
        seenRequests.forEach { req ->
            assertEquals("/api/v1/points", req.path)
            assertEquals("Bearer secret-token", req.authHeader)
            assertFalse("api_key must not appear in query string", req.query.orEmpty().contains("api_key"))
            assertTrue(req.query.orEmpty().contains("start_at="))
        }
    }

    @Test
    fun `401 throws immediately when allowQueryFallback is false`() = runBlocking {
        handler = { StubResponse(401) }

        val api = DawarichApi(baseUrl, "bad-or-bearer-token", allowQueryFallback = false)
        try {
            api.points(startAt = 0, page = 1)
            fail("Expected DawarichHttpException(401)")
        } catch (e: DawarichHttpException) {
            assertEquals(401, e.code)
        }
        assertEquals(1, seenRequests.size)
        assertFalse(seenRequests[0].query.orEmpty().contains("api_key"))
    }

    @Test
    fun `401 falls back to query param once when allowQueryFallback is true`() = runBlocking {
        handler = { req ->
            if ("api_key=legacy-key" in req.query.orEmpty()) {
                StubResponse(200, mapOf("Content-Type" to "application/json"), """{"total_distance_km":1234.5}""")
            } else {
                StubResponse(401)
            }
        }

        val api = DawarichApi(baseUrl, "legacy-key", allowQueryFallback = true)
        val stats = api.stats()
        assertEquals(1234.5, stats.totalDistanceKm, 0.01)
        assertEquals(2, seenRequests.size)
        assertEquals("Bearer legacy-key", seenRequests[0].authHeader)
        assertNull(seenRequests[1].authHeader)
        assertTrue(seenRequests[1].query.orEmpty().contains("api_key=legacy-key"))
    }

    @Test
    fun `429 rate limit throws typed DawarichHttpException`() = runBlocking {
        handler = { StubResponse(429) }

        val api = DawarichApi(baseUrl, "secret-token")
        try {
            api.stats()
            fail("Expected DawarichHttpException(429)")
        } catch (e: DawarichHttpException) {
            assertEquals(429, e.code)
        }
    }

    @Test
    fun `malformed JSON body throws SerializationException and errors never contain apiKey`() = runBlocking {
        handler = { StubResponse(200, mapOf("Content-Type" to "application/json"), "{broken-json") }

        val api = DawarichApi(baseUrl, "super-secret-token-xyz")
        try {
            api.points(startAt = 0, page = 1)
            fail("Expected SerializationException")
        } catch (e: SerializationException) {
            assertFalse((e.message ?: "").contains("super-secret-token-xyz"))
        }
        val err = DawarichHttpException(500)
        assertFalse((err.message ?: "").contains("super-secret-token-xyz"))
    }
}
