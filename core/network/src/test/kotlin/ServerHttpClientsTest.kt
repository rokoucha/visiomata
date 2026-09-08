package net.rokoucha.visiomata.network

import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64

class ServerHttpClientsTest {
    @Before
    fun clearSessions() = ServerHttpClients.clear()

    @Test
    fun `warms up with version and reuses response cookie`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("{\"current\":\"1.0.0\"}")
                    .addHeader("Set-Cookie", "authentik_proxy=session; Path=/; HttpOnly")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().body("[]").build())
            server.enqueue(MockResponse.Builder().body("[]").build())
            val apiRoot = server.url("/api").toString()
            val client = ServerHttpClients.get(apiRoot, "viewer", "app-password")

            client.newCall(Request.Builder().url(server.url("/api/programs")).build()).execute().close()

            val authorization =
                "Basic " + Base64.getEncoder().encodeToString("viewer:app-password".toByteArray())
            val version = server.takeRequest()
            assertEquals("/api/version", version.url.encodedPath)
            assertEquals(authorization, version.headers["Authorization"])
            val programs = server.takeRequest()
            assertEquals("/api/programs", programs.url.encodedPath)
            assertEquals(authorization, programs.headers["Authorization"])
            assertTrue(programs.headers["Cookie"].orEmpty().contains("authentik_proxy=session"))

            val reused = ServerHttpClients.get(apiRoot, "viewer", "app-password")
            assertSame(client, reused)
            reused.newCall(Request.Builder().url(server.url("/api/services")).build()).execute().close()
            assertEquals("/api/services", server.takeRequest().url.encodedPath)
            assertEquals(3, server.requestCount)
        }
    }

    @Test
    fun `explicit version request is not duplicated`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("{\"current\":\"1.0.0\"}").build())
            val client = ServerHttpClients.get(server.url("/api").toString())

            client.newCall(Request.Builder().url(server.url("/api/version")).build()).execute().close()

            assertEquals("/api/version", server.takeRequest().url.encodedPath)
            assertEquals(1, server.requestCount)
        }
    }

    @Test
    fun `cookies are isolated when credentials change`() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(
                MockResponse
                    .Builder()
                    .body("{\"current\":\"1.0.0\"}")
                    .addHeader("Set-Cookie", "authentik_proxy=first-user; Path=/; HttpOnly")
                    .build(),
            )
            server.enqueue(MockResponse.Builder().body("{\"current\":\"1.0.0\"}").build())
            val apiRoot = server.url("/api").toString()

            ServerHttpClients
                .get(apiRoot, "first", "password")
                .newCall(Request.Builder().url(server.url("/api/version")).build())
                .execute()
                .close()
            ServerHttpClients
                .get(apiRoot, "second", "password")
                .newCall(Request.Builder().url(server.url("/api/version")).build())
                .execute()
                .close()

            server.takeRequest()
            val second = server.takeRequest()
            assertEquals(null, second.headers["Cookie"])
            val expected = "Basic " + Base64.getEncoder().encodeToString("second:password".toByteArray())
            assertEquals(expected, second.headers["Authorization"])
        }
    }
}
