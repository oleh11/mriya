package dev.mriya.imports

import dev.mriya.data.GmailClient
import dev.mriya.data.GmailException
import dev.mriya.domain.imports.FlightExtractor
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.time.LocalDate
import java.util.Base64

class GmailClientTest {
    private val clientId = "123456-abc.apps.googleusercontent.com"

    @Test
    fun `pkce challenge matches the RFC 7636 example`() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GmailClient.challengeFor("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test
    fun `sign in asks only for read-only mail and sends no secret`() {
        val url = GmailClient.authUrl(clientId, "CHAL", "STATE")
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        assertTrue(url.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fgmail.readonly"))
        assertTrue(url.contains("code_challenge_method=S256"))
        assertTrue(url.contains("redirect_uri=com.googleusercontent.apps.123456-abc%3A%2Foauth2redirect") || url.contains("redirect_uri=com.googleusercontent.apps.123456-abc:/oauth2redirect"))
        assertFalse(url.contains("secret"))
        assertEquals("com.googleusercontent.apps.123456-abc", GmailClient.redirectScheme(clientId))
    }

    @Test
    fun `redirect is checked for state and refusal`() {
        val ok = "com.googleusercontent.apps.123456-abc:/oauth2redirect?state=S&code=4%2F0AbC"
        assertEquals("4/0AbC", GmailClient.parseRedirect(ok, "S"))
        try { GmailClient.parseRedirect(ok, "OTHER"); fail() } catch (e: GmailException) { assertTrue(e.message!!.contains("match")) }
        try { GmailClient.parseRedirect("x:/cb?error=access_denied&state=S", "S"); fail() } catch (e: GmailException) { assertTrue(e.message!!.contains("not allowed")) }
    }

    private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())

    @Test
    fun `a multipart message gives subject and the plain text body`() {
        val json = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"id":"m1","payload":{"mimeType":"multipart/alternative","headers":[{"name":"Subject","value":"Your flight"},{"name":"From","value":"Air <a@x.test>"}],
              "parts":[{"mimeType":"text/plain","body":{"data":"${b64("ORD to DEN on Mar 12, 2027")}"}},{"mimeType":"text/html","body":{"data":"${b64("<b>html</b>")}"}}]}}""",
        ) as kotlinx.serialization.json.JsonObject
        val m = GmailClient.parseMessage(json)
        assertEquals("Your flight", m.subject)
        assertEquals("ORD to DEN on Mar 12, 2027\n", m.body)
    }

    @Test
    fun `search lists matches then reads each, sending the token and the query`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"messages":[{"id":"a"},{"id":"b"}]}"""))
            server.enqueue(MockResponse().setBody("""{"id":"a","payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"S1"}],"body":{"data":"${b64("UA 123 ORD to DEN Thu, Mar 12, 2027")}"}}}"""))
            server.enqueue(MockResponse().setBody("""{"id":"b","payload":{"mimeType":"text/plain","headers":[{"name":"Subject","value":"S2"}],"body":{"data":"${b64("nothing")}"}}}"""))
            server.start()
            val client = GmailClient(null, clientId, okhttp3.OkHttpClient(), "http://unused", server.url("/").toString().trimEnd('/'), null)
            client.accessToken = "TOK"; client.accessUntil = Long.MAX_VALUE
            val msgs = client.search("flight newer_than:1y")
            assertEquals(listOf("S1", "S2"), msgs.map { it.subject })
            val first = server.takeRequest()
            assertEquals("Bearer TOK", first.getHeader("Authorization"))
            assertTrue(first.path!!.contains("q=flight+newer_than%3A1y") || first.path!!.contains("q=flight%20newer_than%3A1y"))
            assertTrue(server.takeRequest().path!!.contains("format=full"))
            // and the flight reader takes the first message's text
            val airports = setOf("ORD", "DEN")
            assertEquals(1, FlightExtractor.extract(msgs[0].subject + "\n" + msgs[0].body, LocalDate.of(2026, 11, 25)) { it in airports }.size)
        }
    }

    @Test
    fun `a refusal from Google becomes a plain message`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"errors":[{"reason":"accessNotConfigured"}]}}"""))
            server.start()
            val client = GmailClient(null, clientId, okhttp3.OkHttpClient(), "http://unused", server.url("/").toString().trimEnd('/'), null)
            client.accessToken = "TOK"; client.accessUntil = Long.MAX_VALUE
            try { client.search("x"); fail() } catch (e: GmailException) { assertTrue(e.message!!.contains("not enabled")) }
        }
    }
}
