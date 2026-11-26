package com.adaptivefit.personal

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Test

class FitTransportTest {
    @Test fun paidPostIsNeverReplayed() {
        for (code in listOf(307, 408, 503)) {
            MockWebServer().use { server ->
                server.start()
                server.enqueue(MockResponse().setResponseCode(code)
                    .addHeader("Location", server.url("/other")).addHeader("Retry-After", "0"))
                server.enqueue(MockResponse().setResponseCode(200))
                val request = Request.Builder().url(server.url("/coach")).post("synthetic".toRequestBody()).build()
                FitTransport.client(OkHttpClient.Builder()).newCall(request).execute().use {
                    assertEquals(code, it.code)
                }
                assertEquals(1, server.requestCount)
            }
        }
    }
}
