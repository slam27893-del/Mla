package com.myai.assistant.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

data class HttpResult(val code: Int, val body: String)

/** Small seam so providers can be unit-tested without a network stack. */
interface HttpTransport {
    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Int
    ): HttpResult
}

/**
 * OkHttp implementation. No logging interceptor is installed on purpose: request
 * bodies contain user prompts and Authorization headers must never hit Logcat.
 */
class OkHttpTransport : HttpTransport {

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
        timeoutSeconds: Int
    ): HttpResult = withContext(Dispatchers.IO) {
        val client = sharedClient.newBuilder()
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .build()

        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .header("Accept", "application/json")

        headers.forEach { (name, value) -> requestBuilder.header(name, value) }
        val request = requestBuilder.build()

        try {
            client.newCall(request).execute().use { response ->
                HttpResult(response.code, response.body?.string().orEmpty())
            }
        } catch (e: IOException) {
            throw AiException("تعذّر الوصول إلى الخادم: ${e.message ?: "خطأ شبكة"}", e)
        } catch (e: IllegalArgumentException) {
            throw AiException("عنوان الخادم غير صالح.", e)
        }
    }

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()
    }
}
