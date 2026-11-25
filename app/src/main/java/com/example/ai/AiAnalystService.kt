package com.example.ai

import android.util.Log
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout

private const val TAG = "AiAnalystService"

data class GroundedSource(
    val title: String,
    val url: String
)

data class GroundedIntelResult(
    val summary: String,
    val searchQueries: List<String> = emptyList(),
    val sources: List<GroundedSource> = emptyList()
)

sealed interface AiStreamEvent {
    data class Chunk(
        val accumulatedText: String,
        val searchQueries: List<String> = emptyList(),
        val sources: List<GroundedSource> = emptyList()
    ) : AiStreamEvent

    data class Completed(
        val fullText: String,
        val searchQueries: List<String> = emptyList(),
        val sources: List<GroundedSource> = emptyList()
    ) : AiStreamEvent
}

class AiAnalystService {

    private val analystModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch()),
            systemInstruction = content {
                text(
                    """
                    You are PitchEdge Quantitative Sports Analyst, an elite sports modeling and pricing specialist.
                    Provide crisp, data-backed answers using tabular numbers, decimal odds, implied probabilities,
                    Expected Value (+EV %), Fractional Kelly stake sizing, and tactical phase analysis.
                    Use Google Search Grounding whenever the user asks for live scores, latest team sheets,
                    toss updates, injuries, weather, or current bookmaker odds.
                    Keep responses concise, structured, and focused strictly on quantitative sports analytics.
                    """.trimIndent()
                )
            }
        )
    }

    suspend fun fetchGroundedIntel(prompt: String): GroundedIntelResult {
        return try {
            val response = withTimeout(60_000L) {
                analystModel.generateContent(prompt)
            }
            val text = response.text?.takeIf { it.isNotBlank() }
                ?: throw IllegalStateException("Gemini returned an empty response.")
            val (queries, sources) = extractGrounding(response)
            GroundedIntelResult(
                summary = text.trim(),
                searchQueries = queries,
                sources = sources
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Grounded intel timed out", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Grounded intel failed", e)
            throw e
        }
    }

    fun streamAnalystReply(
        fixtureContext: String,
        history: List<Pair<Boolean, String>>,
        userPrompt: String
    ): Flow<AiStreamEvent> = flow {
        val chatHistory: List<Content> = buildList {
            add(content("user") { text("Active Match Context:\n$fixtureContext") })
            add(content("model") { text("Acknowledged. I have the active match telemetry, odds matrix, and phase model loaded.") })
            history.takeLast(8).forEach { (isUser, msg) ->
                add(content(if (isUser) "user" else "model") { text(msg) })
            }
        }

        val chat = analystModel.startChat(history = chatHistory)
        val buffer = StringBuilder()
        var latestQueries: List<String> = emptyList()
        var latestSources: List<GroundedSource> = emptyList()

        try {
            withTimeout(60_000L) {
                chat.sendMessageStream(userPrompt).collect { chunk ->
                    val chunkText = chunk.text.orEmpty()
                    if (chunkText.isNotEmpty()) {
                        buffer.append(chunkText)
                    }
                    val (q, s) = extractGrounding(chunk)
                    if (q.isNotEmpty()) latestQueries = q
                    if (s.isNotEmpty()) latestSources = s
                    if (buffer.isNotEmpty()) {
                        emit(
                            AiStreamEvent.Chunk(
                                accumulatedText = buffer.toString(),
                                searchQueries = latestQueries,
                                sources = latestSources
                            )
                        )
                    }
                }
            }
            val finalText = buffer.toString().trim()
            if (finalText.isBlank()) {
                throw IllegalStateException("Gemini returned an empty response.")
            }
            emit(
                AiStreamEvent.Completed(
                    fullText = finalText,
                    searchQueries = latestQueries,
                    sources = latestSources
                )
            )
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "Analyst chat stream timed out", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Analyst chat stream failed", e)
            throw e
        }
    }

    private fun extractGrounding(response: GenerateContentResponse): Pair<List<String>, List<GroundedSource>> {
        val metadata = response.candidates.firstOrNull()?.groundingMetadata
        val queries = metadata?.webSearchQueries ?: emptyList()
        val sources = metadata?.groundingChunks
            ?.mapNotNull { chunk ->
                val web = chunk.web
                val uri = web?.uri
                if (!uri.isNullOrBlank()) {
                    GroundedSource(
                        title = web.title?.takeIf { it.isNotBlank() } ?: uri,
                        url = uri
                    )
                } else null
            }
            ?.distinctBy { it.url }
            ?: emptyList()
        return queries to sources
    }
}
