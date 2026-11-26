package com.adaptivefit.personal

import com.facebook.react.modules.network.OkHttpClientProvider
import okhttp3.OkHttpClient
import okhttp3.RequestBody
import okio.BufferedSink
import java.util.concurrent.TimeUnit

object FitTransport {
    fun client(builder: OkHttpClient.Builder = OkHttpClientProvider.createClientBuilder()): OkHttpClient =
        builder.followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .callTimeout(90, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val body = request.body
                if (request.method != "POST" || body == null) return@addInterceptor chain.proceed(request)
                val oneShotBody = object : RequestBody() {
                    override fun contentType() = body.contentType()
                    override fun contentLength() = body.contentLength()
                    override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
                    override fun isOneShot() = true
                }
                chain.proceed(request.newBuilder().post(oneShotBody).build())
            }.build()
}
