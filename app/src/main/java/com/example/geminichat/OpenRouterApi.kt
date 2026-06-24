package com.example.geminichat

import retrofit2.http.Body
import retrofit2.http.Header
import retrofit2.http.POST

data class OpenRouterRequest(
    val model: String,
    val messages: List<OpenRouterMessage>
)

data class OpenRouterMessage(
    val role: String,
    val content: String
)

data class OpenRouterResponse(
    val choices: List<OpenRouterChoice>
)

data class OpenRouterChoice(
    val message: OpenRouterMessage
)

data class OpenRouterErrorResponse(
    val error: OpenRouterError
)

data class OpenRouterError(
    val message: String,
    val code: Int
)

interface OpenRouterApi {
    @POST("chat/completions")
    suspend fun getCompletion(
        @Header("Authorization") auth: String,
        @Header("HTTP-Referer") referer: String = "https://example.com",
        @Header("X-Title") title: String = "Gemini Chat App",
        @Body request: OpenRouterRequest
    ): OpenRouterResponse
}
