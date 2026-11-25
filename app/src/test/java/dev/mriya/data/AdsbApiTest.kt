package dev.mriya.data

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

class AdsbApiTest {

    data class StubResponse(val code: Int, val body: String = "")

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private lateinit var baseUrl: String
    private val paths = CopyOnWriteArrayList<String>()
    @Volatile
    private var handler: (String) -> StubResponse = { StubResponse(404) }

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        worker = thread(isDaemon = true, name = "adsb-test-http") {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                sock.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val reqLine = reader.readLine() ?: return@use
                    val path = reqLine.split(' ').getOrNull(1) ?: "/"
                    while (true) {
                        val l = reader.readLine() ?: break
                        if (l.isEmpty()) break
                    }
                    paths += path
                    val resp = handler(path)
                    val bytes = resp.body.toByteArray(Charsets.UTF_8)
                    val out = s.getOutputStream()
                    out.write(
                        ("HTTP/1.1 ${resp.code} TEST\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n")
                            .toByteArray(Charsets.US_ASCII)
                    )
                    if (bytes.isNotEmpty()) out.write(bytes)
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
    fun `iataToIcaoCallsign and normalizeTrackQuery classify callsigns and tails`() {
        assertEquals("UAL123", iataToIcaoCallsign("ua", "123"))
        assertEquals("AUI401", iataToIcaoCallsign("PS", "401"))
        assertEquals("DLH400", iataToIcaoCallsign("DLH", "400"))

        assertEquals(AdsbQuery.Callsign("UAL123"), normalizeTrackQuery("UA 123"))
        assertEquals(AdsbQuery.Callsign("UAL123"), normalizeTrackQuery("ual123"))
        assertEquals(AdsbQuery.Registration("N27958"), normalizeTrackQuery("N27958"))
        assertEquals(AdsbQuery.Registration("UR-82060"), normalizeTrackQuery("ur-82060"))
        assertEquals(AdsbQuery.Registration("D-ABYA"), normalizeTrackQuery("reg:d-abya"))
        assertNull(normalizeTrackQuery("  "))
    }

    @Test
    fun `parses airborne aircraft telemetry by callsign`() = runBlocking {
        handler = {
            StubResponse(
                200,
                """{
                    "ac": [{
                        "flight": "UAL123  ",
                        "r": "N27958",
                        "t": "B789",
                        "lat": 51.47,
                        "lon": -0.45,
                        "alt_baro": 36000,
                        "gs": 487.4,
                        "track": 94.2,
                        "squawk": "1234"
                    }]
                }"""
            )
        }

        val api = AdsbApi(AdsbFeed.ADSB_LOL, baseUrlOverride = baseUrl)
        val ac = api.lookupByCallsign("UAL123")
        assertNotNull(ac)
        ac!!
        assertEquals("/v2/callsign/UAL123", paths[0])
        assertEquals("UAL123", ac.callsign)
        assertEquals("N27958", ac.registration)
        assertEquals("B789", ac.aircraftType)
        assertEquals(51.47, ac.lat, 0.001)
        assertEquals(-0.45, ac.lon, 0.001)
        assertEquals(36000, ac.altitudeFt)
        assertEquals(487, ac.groundSpeedKt)
        assertEquals(94.2f, ac.headingDeg ?: 0f, 0.01f)
    }

    @Test
    fun `handles ground altitude, empty ac array, registration lookup, and http errors`() = runBlocking {
        handler = { path ->
            if (path.startsWith("/v2/reg/")) {
                StubResponse(200, """{"ac":[{"flight":"UR82060 ","r":"UR-82060","t":"A225","lat":50.59,"lon":30.20,"alt_baro":"ground"}]}""")
            } else {
                StubResponse(200, """{"ac":[]}""")
            }
        }

        val api = AdsbApi(AdsbFeed.AIRPLANES_LIVE, baseUrlOverride = baseUrl)
        assertNull(api.lookupByCallsign("MISSING1"))
        val ground = api.lookupByRegistration("UR-82060")
        assertEquals(0, ground?.altitudeFt)
        assertEquals("UR-82060", ground?.registration)
        assertTrue(paths[1] == "/v2/reg/UR-82060")

        handler = { StubResponse(503) }
        try {
            api.lookupByCallsign("UAL123")
            fail("Expected AdsbException")
        } catch (_: AdsbException) {
            // expected
        }
    }
}
