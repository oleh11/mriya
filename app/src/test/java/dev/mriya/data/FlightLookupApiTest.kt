package dev.mriya.data

import kotlinx.coroutines.runBlocking
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

class FlightLookupApiTest {

    data class RecordedRequest(val path: String, val query: String?, val headers: Map<String, String>)
    data class StubResponse(val code: Int, val body: String = "")

    private lateinit var server: ServerSocket
    private lateinit var worker: Thread
    private lateinit var baseUrl: String
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    @Volatile
    private var handler: (RecordedRequest) -> StubResponse = { StubResponse(404) }

    @Before
    fun setUp() {
        server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        baseUrl = "http://127.0.0.1:${server.localPort}"
        worker = thread(isDaemon = true, name = "lookup-test-http") {
            while (!server.isClosed) {
                val sock = try {
                    server.accept()
                } catch (_: SocketException) {
                    break
                }
                sock.use { s ->
                    val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
                    val reqLine = reader.readLine() ?: return@use
                    val target = reqLine.split(' ').getOrNull(1) ?: "/"
                    val hdrs = HashMap<String, String>()
                    while (true) {
                        val line = reader.readLine() ?: break
                        if (line.isEmpty()) break
                        val idx = line.indexOf(':')
                        if (idx > 0) hdrs[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
                    }
                    val uri = URI.create("http://localhost$target")
                    val req = RecordedRequest(uri.path, uri.query, hdrs)
                    requests += req
                    val resp = handler(req)
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
    fun `parseFlightDesignator handles standard and alphanumeric carriers`() {
        assertEquals("UA" to "123", parseFlightDesignator("ua 123"))
        assertEquals("LH" to "400", parseFlightDesignator("LH400"))
        assertEquals("B6" to "524", parseFlightDesignator("B6 524"))
        assertEquals("B6" to "524", parseFlightDesignator("B6524"))
        assertEquals("7W" to "101", parseFlightDesignator("7W-101"))
        assertEquals("U2" to "45A", parseFlightDesignator("U245A"))
        assertNull(parseFlightDesignator("12345"))
        assertNull(parseFlightDesignator("ORD"))
    }

    @Test
    fun `AeroDataBox parses route, aircraft, gates, and omits key from errors`() = runBlocking {
        handler = {
            StubResponse(
                200,
                """[{
                    "number": "UA 123",
                    "callSign": "UAL123",
                    "status": "Arrived",
                    "airline": { "iata": "UA" },
                    "aircraft": { "model": "Boeing 787-9", "reg": "N27958" },
                    "departure": { "airport": { "iata": "ORD" }, "terminal": "1", "gate": "B12", "scheduledTime": { "utc": "2026-03-12 14:30Z" } },
                    "arrival": { "airport": { "iata": "SFO" }, "terminal": "3", "gate": "F8", "revisedTime": { "utc": "2026-03-12 19:05Z" } }
                }]"""
            )
        }

        val api = FlightLookupApi(
            provider = FlightLookupProvider.AERODATABOX,
            apiKey = "rapid-secret-key",
            aeroDataBoxHost = "custom.rapid.local",
            baseUrlOverride = baseUrl,
        )
        val res = api.lookup("UA 123", "2026-03-12")

        assertEquals("ORD", res.fromIata)
        assertEquals("SFO", res.toIata)
        assertEquals("UA", res.airlineIata)
        assertEquals("123", res.flightNumber)
        assertEquals("BOEING 787-9", res.aircraftType)
        assertEquals("N27958", res.registration)
        assertEquals("UAL123", res.callsign)
        assertEquals("1", res.departureTerminal)
        assertEquals("B12", res.departureGate)
        assertTrue((res.departTs ?: 0L) > 0L)
        assertEquals("/flights/number/UA123/2026-03-12", requests[0].path)
        assertEquals("rapid-secret-key", requests[0].headers["x-rapidapi-key"])
        assertEquals("custom.rapid.local", requests[0].headers["x-rapidapi-host"])

        handler = { StubResponse(401) }
        try {
            api.lookup("UA 123", "2026-03-12")
            fail("Expected FlightLookupException")
        } catch (e: FlightLookupException) {
            assertFalse((e.message ?: "").contains("rapid-secret-key"))
        }
    }

    @Test
    fun `FlightAware parses AeroAPI v4 response and uses x-apikey header`() = runBlocking {
        handler = {
            StubResponse(
                200,
                """{
                    "flights": [{
                        "ident_icao": "DLH400",
                        "operator_iata": "LH",
                        "flight_number": "400",
                        "aircraft_type": "B748",
                        "registration": "D-ABYA",
                        "status": "En Route",
                        "scheduled_out": "2026-06-01T08:50:00Z",
                        "estimated_in": "2026-06-01T17:15:00Z",
                        "origin": { "code_iata": "FRA" },
                        "destination": { "code_iata": "JFK" },
                        "terminal_origin": "1",
                        "gate_origin": "Z50"
                    }]
                }"""
            )
        }

        val api = FlightLookupApi(
            provider = FlightLookupProvider.FLIGHTAWARE,
            apiKey = "fa-secret-key",
            baseUrlOverride = baseUrl,
        )
        val res = api.lookup("LH400", "2026-06-01")

        assertEquals("FRA", res.fromIata)
        assertEquals("JFK", res.toIata)
        assertEquals("LH", res.airlineIata)
        assertEquals("400", res.flightNumber)
        assertEquals("B748", res.aircraftType)
        assertEquals("D-ABYA", res.registration)
        assertEquals("DLH400", res.callsign)
        assertEquals("1", res.departureTerminal)
        assertEquals("Z50", res.departureGate)
        assertEquals("/flights/LH400", requests[0].path)
        assertEquals("fa-secret-key", requests[0].headers["x-apikey"])
        assertTrue(requests[0].query.orEmpty().contains("start=2026-06-01T00:00:00Z"))
    }
}
