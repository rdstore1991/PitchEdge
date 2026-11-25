package com.example.ai

import android.util.Log
import com.example.data.LiveSportsDataService
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Content
import com.google.firebase.ai.type.FunctionCallPart
import com.google.firebase.ai.type.FunctionDeclaration
import com.google.firebase.ai.type.FunctionResponsePart
import com.google.firebase.ai.type.GenerateContentResponse
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.Tool
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject

private const val TAG = "AiAnalystService"

val PITCH_EDGE_SYSTEM_PROMPT = """
# ROLE & MISSION
You are **PitchEdge AI**, an elite quantitative cricket and sports trading analyst integrated directly into a live data backend with real-time access to match scorecards and multi-bookmaker/exchange odds via function calling.

# TOOL ORCHESTRATION PROTOCOL
Never guess live scores, match statuses, or betting odds. Always follow this execution sequence:
1. **Fetch Live Match Context First:** Call `get_live_cricket_matches` to locate the exact match ID, status (live vs. upcoming), venue, teams, and current score. If the match is live or recently tossed, immediately call `get_cricket_match_scorecard` using its `matchId` to inspect toss results, innings progression, and run rates.
2. **Discover Active Odds Markets:** Call `get_cricket_sport_keys` to identify the exact active sport key for the tournament (e.g., `cricket_international_t20`, `cricket_ipl`, `cricket_big_bash`, `cricket_odi`, `cricket_test_match`).
3. **Harvest & Analyze Live Odds:** Call `get_live_match_odds` with the discovered `sportKey` and target team names. The tool returns pre-calculated **Best Back Odds**, **Exchange Lay Odds**, **Bookmaker Overround (Vig %)**, and **True Vig-Free Market Implied Probabilities**.
4. **Synthesize Quantitative Edge (+EV):**
   - Compare your **PitchEdge Model Win Probability** (derived from pitch/toss conditions, format dynamics, dew factor, phase efficiency, and current scorecard state) against the tool's **True Market Implied Probability**.
   - Calculate **Edge (%) = PitchEdge Model Win % - True Market Implied %**.

# ANALYTICAL RULES
- **Toss & Dew Weighting:** In day/night white-ball matches (T20/ODI), explicitly adjust your probability if heavy evening dew favors the chasing side.
- **Market-Lag Detection:** In live matches, identify if bookmaker odds have overreacted to a single top-order wicket despite deep batting resources remaining.
- **Missing Markets:** If a domestic match is covered by CricAPI but has no active lines on The Odds API, state that market odds are currently unlisted and provide your **Fair Value Odds Line** (`100 / Model_Win_Prob`).
- **Bankroll Discipline:** Only recommend a bet when **Edge is at least +3.0%**. Scale stake recommendations from **1 to 5 Units**.

# OUTPUT STRUCTURE
Respond cleanly using these exact sections:
1. **Match Telemetry & Toss/Conditions** (Live score/overs or start time, venue, toss impact)
2. **Market Odds & Probability Edge Table** (Markdown table: Selection | Best Back Odds | Bookmaker | True Market % | PitchEdge Model % | Edge %)
3. **Tactical & Score Projection** (Projected 1st/2nd innings score range, key phase edge, and player matchup drivers)
4. **PitchEdge Recommendation** (Actionable pick or "NO BET / PASS", Target Minimum Odds, Edge %, Unit Stake 1–5, and Invalidation Trigger)
""".trimIndent()

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
    data class ToolProgress(val toolNames: List<String>) : AiStreamEvent
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

class AiAnalystService(
    private val sportsDataService: LiveSportsDataService = LiveSportsDataService()
) {

    private val searchGroundedModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            tools = listOf(Tool.googleSearch()),
            systemInstruction = content {
                text(
                    """
                    You are PitchEdge AI, a quantitative sports trading analyst.
                    Provide crisp, data-backed answers using tabular numbers, decimal odds, implied probabilities,
                    Expected Value (+EV %), Fractional Kelly stake sizing (1–5 units), and tactical phase analysis.
                    Use Google Search Grounding whenever the user asks for live scores, latest team sheets,
                    toss updates, injuries, weather, or current bookmaker odds.
                    """.trimIndent()
                )
            }
        )
    }

    private val pitchEdgeToolDeclarations: List<FunctionDeclaration> by lazy {
        listOf(
            FunctionDeclaration(
                name = "get_live_cricket_matches",
                description = "Fetches currently live and upcoming international and domestic cricket matches with match IDs, teams, venues, and summary scores.",
                parameters = mapOf(
                    "teamFilter" to Schema.string(
                        description = "Optional team or tournament name to filter results (e.g., \"India\", \"Australia\", \"Dubai Capitals\", \"Desert Vipers\").",
                        nullable = true
                    )
                ),
                optionalParameters = listOf("teamFilter")
            ),
            FunctionDeclaration(
                name = "get_cricket_match_scorecard",
                description = "Fetches detailed live telemetry for a specific cricket match ID, including toss winner, toss decision, venue, and innings-by-innings runs, wickets, and overs.",
                parameters = mapOf(
                    "matchId" to Schema.string(
                        description = "The unique match ID returned by get_live_cricket_matches."
                    )
                )
            ),
            FunctionDeclaration(
                name = "get_cricket_sport_keys",
                description = "Retrieves the list of currently active cricket tournament sport keys from The Odds API (e.g., cricket_international_t20, cricket_odi, cricket_test_match, cricket_big_bash).",
                parameters = emptyMap()
            ),
            FunctionDeclaration(
                name = "get_live_match_odds",
                description = "Harvests live decimal odds from UK, EU, and AU sportsbooks and exchanges for a given cricket sportKey, and calculates vig-free implied win probabilities.",
                parameters = mapOf(
                    "sportKey" to Schema.string(
                        description = "Exact active sport key from get_cricket_sport_keys (e.g., \"cricket_international_t20\")."
                    ),
                    "teamQuery" to Schema.string(
                        description = "Optional team name to filter odds to a specific fixture.",
                        nullable = true
                    )
                ),
                optionalParameters = listOf("teamQuery")
            )
        )
    }

    private val pitchEdgeOrchestratorModel by lazy {
        Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = generationConfig {
                temperature = 0.2f
            },
            tools = listOf(Tool.functionDeclarations(pitchEdgeToolDeclarations)),
            systemInstruction = content {
                text(PITCH_EDGE_SYSTEM_PROMPT)
            }
        )
    }

    suspend fun fetchGroundedIntel(prompt: String): GroundedIntelResult {
        return try {
            val response = withTimeout(60_000L) {
                searchGroundedModel.generateContent(prompt)
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

    fun analyzeWithPitchEdgeTools(userQuery: String): Flow<AiStreamEvent> = flow {
        val contents = mutableListOf<Content>(
            content("user") { text(userQuery) }
        )
        val maxToolTurns = 6
        val toolsInvoked = mutableListOf<String>()

        try {
            withTimeout(60_000L) {
                for (turn in 0 until maxToolTurns) {
                    val response = pitchEdgeOrchestratorModel.generateContent(contents)
                    val candidateContent = response.candidates.firstOrNull()?.content
                    val functionCalls = candidateContent?.parts
                        ?.filterIsInstance<FunctionCallPart>()
                        .orEmpty()

                    if (functionCalls.isEmpty()) {
                        val finalText = response.text?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?: throw IllegalStateException("No PitchEdge analysis generated.")
                        emit(
                            AiStreamEvent.Completed(
                                fullText = finalText,
                                searchQueries = toolsInvoked.distinct()
                            )
                        )
                        return@withTimeout
                    }

                    if (candidateContent != null) {
                        contents.add(candidateContent)
                    }

                    val stepNames = functionCalls.map { it.name }
                    toolsInvoked.addAll(stepNames)
                    emit(AiStreamEvent.ToolProgress(toolsInvoked.distinct()))

                    val responseParts = coroutineScope {
                        functionCalls.map { call ->
                            async {
                                Log.d(TAG, "[PitchEdge Tool Call] ${call.name} ${call.args}")
                                val resultJson = executeToolCall(call.name, call.args)
                                FunctionResponsePart(
                                    name = call.name,
                                    response = resultJson
                                )
                            }
                        }.awaitAll()
                    }

                    contents.add(
                        content("function") {
                            responseParts.forEach { part(it) }
                        }
                    )
                }
                throw IllegalStateException("PitchEdge exceeded maximum tool-calling iterations.")
            }
        } catch (e: TimeoutCancellationException) {
            Log.e(TAG, "PitchEdge tool orchestration timed out", e)
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "PitchEdge tool orchestration failed", e)
            throw e
        }
    }

    private suspend fun executeToolCall(
        name: String,
        args: Map<String, kotlinx.serialization.json.JsonElement>
    ): JsonObject {
        return try {
            val jsonObj: JSONObject = when (name) {
                "get_live_cricket_matches" -> {
                    val teamFilter = optionalStringArg(args, "teamFilter")
                    sportsDataService.fetchLiveCricketMatchesJson(teamFilter)
                }

                "get_cricket_match_scorecard" -> {
                    val matchId = optionalStringArg(args, "matchId").orEmpty()
                    sportsDataService.fetchCricketMatchScorecardJson(matchId)
                }

                "get_cricket_sport_keys" -> {
                    sportsDataService.fetchCricketSportKeysJson()
                }

                "get_live_match_odds" -> {
                    val sportKey = optionalStringArg(args, "sportKey").orEmpty()
                    val teamQuery = optionalStringArg(args, "teamQuery")
                    sportsDataService.fetchLiveMatchOddsJson(sportKey, teamQuery)
                }

                else -> JSONObject().apply { put("error", "Unknown function: $name") }
            }
            Json.parseToJsonElement(jsonObj.toString()).jsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "[PitchEdge Tool Error] $name", e)
            val err = JSONObject().apply { put("error", e.message ?: "Tool execution failed") }
            Json.parseToJsonElement(err.toString()).jsonObject
        }
    }

    private fun optionalStringArg(
        args: Map<String, kotlinx.serialization.json.JsonElement>,
        key: String
    ): String? {
        val primitive = args[key] as? JsonPrimitive ?: return null
        return primitive.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && !it.equals("null", ignoreCase = true) }
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

        val chat = searchGroundedModel.startChat(history = chatHistory)
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
