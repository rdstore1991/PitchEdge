package com.example.data

import android.util.Log
import com.example.BuildConfig
import com.example.model.BookmakerOddsRow
import com.example.model.FixtureOverview
import com.example.model.FormatFilter
import com.example.model.SportFilter
import com.example.model.StatusFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

private const val TAG = "LiveSportsDataService"
private const val CRIC_BASE_URL = "https://api.cricapi.com/v1"
private const val ODDS_BASE_URL = "https://api.the-odds-api.com/v4"

class LiveSportsDataService(
    private val cache: MemoryTtlCache = MemoryTtlCache()
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    fun isCricApiConfigured(): Boolean {
        val key = BuildConfig.CRIC_API_KEY
        return key.isNotBlank() && key != "MY_CRIC_API_KEY"
    }

    fun isOddsApiConfigured(): Boolean {
        val key = BuildConfig.ODDS_API_KEY
        return key.isNotBlank() && key != "MY_ODDS_API_KEY"
    }

    suspend fun fetchLiveCricketMatchesJson(teamFilter: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isCricApiConfigured()) {
                Log.w(TAG, "CRIC_API_KEY is not configured in Secrets.")
                return@withContext JSONObject().apply {
                    put("error", "CRIC_API_KEY is not set. Add CRIC_API_KEY in the AI Studio Secrets panel.")
                }
            }

            val cacheKey = "pitchedge:cricapi:currentMatches"
            var matchesArray = cache.get<JSONArray>(cacheKey)

            if (matchesArray == null) {
                val url = "$CRIC_BASE_URL/currentMatches?apikey=${
                    URLEncoder.encode(BuildConfig.CRIC_API_KEY, "UTF-8")
                }&offset=0"
                val bodyStr = executeGet(url)
                val payload = JSONObject(bodyStr)
                if (payload.optString("status") != "success") {
                    val reason = payload.optString("reason", "Failed to fetch matches")
                    return@withContext JSONObject().apply { put("error", "CricAPI Error: $reason") }
                }

                val rawData = payload.optJSONArray("data") ?: JSONArray()
                val normalized = JSONArray()
                for (i in 0 until rawData.length()) {
                    val m = rawData.getJSONObject(i)
                    normalized.put(
                        JSONObject().apply {
                            put("id", m.optString("id"))
                            put("name", m.optString("name"))
                            put("matchType", m.optString("matchType"))
                            put("status", m.optString("status"))
                            put("venue", m.optString("venue"))
                            put("dateTimeGMT", m.optString("dateTimeGMT"))
                            put("teams", m.optJSONArray("teams") ?: JSONArray())
                            put("score", m.optJSONArray("score") ?: JSONArray())
                            put("matchStarted", m.optBoolean("matchStarted", false))
                            put("matchEnded", m.optBoolean("matchEnded", false))
                        }
                    )
                }
                matchesArray = normalized
                cache.set(cacheKey, matchesArray, 30L)
            }

            val filtered = JSONArray()
            val q = teamFilter?.trim()?.lowercase(Locale.US).orEmpty()
            for (i in 0 until matchesArray.length()) {
                val m = matchesArray.getJSONObject(i)
                val matchesQuery = if (q.isEmpty()) {
                    true
                } else {
                    val nameHit = m.optString("name").lowercase(Locale.US).contains(q)
                    val teams = m.optJSONArray("teams") ?: JSONArray()
                    val teamHit = (0 until teams.length()).any { idx ->
                        teams.optString(idx).lowercase(Locale.US).contains(q)
                    }
                    nameHit || teamHit
                }
                if (matchesQuery && filtered.length() < 20) {
                    filtered.put(m)
                }
            }

            JSONObject().apply {
                put("count", filtered.length())
                put("matches", filtered)
            }
        }

    suspend fun fetchCricketMatchScorecardJson(matchId: String): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isCricApiConfigured()) {
                return@withContext JSONObject().apply {
                    put("error", "CRIC_API_KEY is not set. Add CRIC_API_KEY in the AI Studio Secrets panel.")
                }
            }
            val cacheKey = "pitchedge:cricapi:match_info:$matchId"
            cache.get<JSONObject>(cacheKey)?.let { return@withContext it }

            val url = "$CRIC_BASE_URL/match_info?apikey=${
                URLEncoder.encode(BuildConfig.CRIC_API_KEY, "UTF-8")
            }&id=${URLEncoder.encode(matchId, "UTF-8")}"
            val payload = JSONObject(executeGet(url))
            val d = payload.optJSONObject("data")
            if (payload.optString("status") != "success" || d == null) {
                val reason = payload.optString("reason", "Match not found")
                return@withContext JSONObject().apply { put("error", "CricAPI Match Info Error: $reason") }
            }

            val normalized = JSONObject().apply {
                put("id", d.optString("id"))
                put("name", d.optString("name"))
                put("matchType", d.optString("matchType"))
                put("status", d.optString("status"))
                put("venue", d.optString("venue"))
                put("dateTimeGMT", d.optString("dateTimeGMT"))
                put("teams", d.optJSONArray("teams") ?: JSONArray())
                put("tossWinner", d.optString("tossWinner").ifBlank { "Pending" })
                put("tossChoice", d.optString("tossChoice").ifBlank { "Pending" })
                put("matchWinner", if (d.isNull("matchWinner")) JSONObject.NULL else d.optString("matchWinner"))
                put("score", d.optJSONArray("score") ?: JSONArray())
            }
            cache.set(cacheKey, normalized, 25L)
            normalized
        }

    suspend fun fetchCricketSportKeysJson(): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isOddsApiConfigured()) {
                return@withContext JSONObject().apply {
                    put("error", "ODDS_API_KEY is not set. Add ODDS_API_KEY in the AI Studio Secrets panel.")
                }
            }
            val cacheKey = "pitchedge:oddsapi:cricket_sports"
            cache.get<JSONArray>(cacheKey)?.let { cached ->
                return@withContext JSONObject().apply { put("sports", cached) }
            }

            val url = "$ODDS_BASE_URL/sports/?apiKey=${URLEncoder.encode(BuildConfig.ODDS_API_KEY, "UTF-8")}"
            val allSports = JSONArray(executeGet(url))
            val cricketSports = JSONArray()
            for (i in 0 until allSports.length()) {
                val s = allSports.getJSONObject(i)
                if (s.optString("group").equals("cricket", ignoreCase = true) && s.optBoolean("active", false)) {
                    cricketSports.put(s)
                }
            }
            cache.set(cacheKey, cricketSports, 3600L)
            JSONObject().apply { put("sports", cricketSports) }
        }

    suspend fun fetchLiveMatchOddsJson(sportKey: String, teamQuery: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isOddsApiConfigured()) {
                return@withContext JSONObject().apply {
                    put("error", "ODDS_API_KEY is not set. Add ODDS_API_KEY in the AI Studio Secrets panel.")
                }
            }

            val cacheKey = "pitchedge:oddsapi:odds:$sportKey"
            var events = cache.get<JSONArray>(cacheKey)

            if (events == null) {
                val url = "$ODDS_BASE_URL/sports/${
                    URLEncoder.encode(sportKey, "UTF-8")
                }/odds/?apiKey=${
                    URLEncoder.encode(BuildConfig.ODDS_API_KEY, "UTF-8")
                }&regions=uk,eu,au&markets=h2h&oddsFormat=decimal"
                events = JSONArray(executeGet(url))
                cache.set(cacheKey, events, 30L)
            }

            val q = teamQuery?.trim()?.lowercase(Locale.US).orEmpty()
            val analyzedEvents = JSONArray()

            for (i in 0 until events.length()) {
                val event = events.getJSONObject(i)
                val homeTeam = event.optString("home_team")
                val awayTeam = event.optString("away_team")
                if (q.isNotEmpty() &&
                    !homeTeam.lowercase(Locale.US).contains(q) &&
                    !awayTeam.lowercase(Locale.US).contains(q)
                ) {
                    continue
                }

                val bestBack = mutableMapOf<String, Pair<Double, String>>()
                val exchangeLay = mutableMapOf<String, Pair<Double, String>>()
                var bookmakerCount = 0
                val bookmakers = event.optJSONArray("bookmakers") ?: JSONArray()

                for (bIdx in 0 until bookmakers.length()) {
                    val bookie = bookmakers.getJSONObject(bIdx)
                    val bookieTitle = bookie.optString("title")
                    val markets = bookie.optJSONArray("markets") ?: JSONArray()
                    for (mIdx in 0 until markets.length()) {
                        val mkt = markets.getJSONObject(mIdx)
                        val mktKey = mkt.optString("key")
                        val outcomes = mkt.optJSONArray("outcomes") ?: JSONArray()
                        if (mktKey == "h2h" && outcomes.length() > 0) {
                            bookmakerCount++
                            for (oIdx in 0 until outcomes.length()) {
                                val o = outcomes.getJSONObject(oIdx)
                                val name = o.optString("name")
                                val price = o.optDouble("price", 0.0)
                                val prev = bestBack[name]
                                if (price > 0.0 && (prev == null || price > prev.first)) {
                                    bestBack[name] = price to bookieTitle
                                }
                            }
                        } else if (mktKey == "h2h_lay" && outcomes.length() > 0) {
                            for (oIdx in 0 until outcomes.length()) {
                                val o = outcomes.getJSONObject(oIdx)
                                val name = o.optString("name")
                                val price = o.optDouble("price", 0.0)
                                val prev = exchangeLay[name]
                                if (price > 0.0 && (prev == null || price < prev.first)) {
                                    exchangeLay[name] = price to bookieTitle
                                }
                            }
                        }
                    }
                }

                val rawEntries = bestBack.entries.map { (name, pair) ->
                    Triple(name, pair, 1.0 / pair.first)
                }
                val totalImpliedSum = rawEntries.sumOf { it.third }
                val overroundPercent = if (totalImpliedSum > 0.0) {
                    ((totalImpliedSum - 1.0) * 100.0 * 100.0).toInt() / 100.0
                } else 0.0

                val marketProbsArray = JSONArray()
                rawEntries.forEach { (name, pair, rawImplied) ->
                    val rawPct = Math.round(rawImplied * 10000.0) / 100.0
                    val vigFreePct = if (totalImpliedSum > 0.0) {
                        Math.round((rawImplied / totalImpliedSum) * 10000.0) / 100.0
                    } else 0.0

                    marketProbsArray.put(
                        JSONObject().apply {
                            put("selection", name)
                            put("bestBackOdds", pair.first)
                            put("bestBookmaker", pair.second)
                            put("exchangeLayOdds", exchangeLay[name]?.first ?: JSONObject.NULL)
                            put("rawImpliedPercent", rawPct)
                            put("trueVigFreeMarketPercent", vigFreePct)
                        }
                    )
                }

                analyzedEvents.put(
                    JSONObject().apply {
                        put("eventId", event.optString("id"))
                        put("sportKey", event.optString("sport_key"))
                        put("sportTitle", event.optString("sport_title"))
                        put("commenceTime", event.optString("commence_time"))
                        put("homeTeam", homeTeam)
                        put("awayTeam", awayTeam)
                        put("marketOverroundPercent", overroundPercent)
                        put("marketProbabilities", marketProbsArray)
                        put("bookmakerCount", bookmakerCount)
                    }
                )
            }

            JSONObject().apply {
                put("sportKey", sportKey)
                put("eventsFound", analyzedEvents.length())
                put("events", analyzedEvents)
            }
        }

    suspend fun fetchLiveCricketSlateForUi(): List<FixtureOverview> {
        val matchesJson = fetchLiveCricketMatchesJson(null)
        if (matchesJson.has("error")) {
            throw IllegalStateException(matchesJson.getString("error"))
        }
        val arr = matchesJson.optJSONArray("matches") ?: return emptyList()
        val list = mutableListOf<FixtureOverview>()
        for (i in 0 until arr.length().coerceAtMost(8)) {
            val m = arr.getJSONObject(i)
            val teams = m.optJSONArray("teams")
            val home = teams?.optString(0)?.takeIf { it.isNotBlank() } ?: "Team A"
            val away = teams?.optString(1)?.takeIf { it.isNotBlank() } ?: "Team B"
            val scoreArr = m.optJSONArray("score")
            val scoreText = if (scoreArr != null && scoreArr.length() > 0) {
                val s0 = scoreArr.getJSONObject(0)
                "${s0.optInt("r")}/${s0.optInt("w")} (${s0.optDouble("o")} ov)"
            } else {
                m.optString("status").ifBlank { "Scheduled" }
            }
            val started = m.optBoolean("matchStarted", false)
            val ended = m.optBoolean("matchEnded", false)
            val matchType = m.optString("matchType", "T20").uppercase(Locale.US)
            val format = when {
                matchType in setOf("TEST", "ODI", "T20I") -> FormatFilter.INTERNATIONAL
                m.optString("name").contains("League", ignoreCase = true) ||
                    m.optString("name").contains("ILT20", ignoreCase = true) ||
                    m.optString("name").contains("IPL", ignoreCase = true) ||
                    m.optString("name").contains("BBL", ignoreCase = true) -> FormatFilter.T20_LEAGUES
                else -> FormatFilter.DOMESTIC
            }

            list.add(
                FixtureOverview(
                    id = "cricapi_${m.optString("id")}",
                    sport = SportFilter.CRICKET,
                    format = format,
                    status = if (started && !ended) StatusFilter.LIVE_NOW else StatusFilter.UPCOMING_TODAY,
                    hasPositiveEdge = false,
                    leagueTitle = "${matchType} • CricAPI Live",
                    matchLabel = "${home.take(3).uppercase()} vs ${away.take(3).uppercase()}",
                    homeCode = home.take(3).uppercase(),
                    homeName = home,
                    homeRecord = m.optString("status"),
                    awayCode = away.take(3).uppercase(),
                    awayName = away,
                    awayRecord = m.optString("dateTimeGMT").take(16),
                    liveScoreOrTimer = scoreText,
                    oversOrPeriod = m.optString("status"),
                    venue = m.optString("venue").ifBlank { "Venue TBA" },
                    weatherLine = "Run PitchEdge Agent for live conditions & odds",
                    tossStatus = if (started) "Live In Progress" else "Pre-Toss",
                    homeModelPct = 50.0f,
                    awayModelPct = 50.0f,
                    marketHomePct = 50.0f,
                    marketAwayPct = 50.0f,
                    topEdgeBadge = if (started && !ended) "LIVE" else "CRICAPI",
                    bestPickTitle = "Tap 'Run PitchEdge Agent' to fetch Odds API vig-free lines & scorecard edge",
                    bestPickFairOdds = 2.00,
                    bestPickMarketOdds = 2.00,
                    bestPickConfidence = "Pending Agent",
                    bestPickUnits = 1.0f,
                    bestPickRiskWarning = "Live CricAPI fixture loaded. Trigger PitchEdge Agent to check The Odds API markets and calculate +EV.",
                    defaultFirstInnScore = 165,
                    defaultSecondInnScore = 158,
                    scoreUnitLabel = "Runs",
                    bookmakerRows = listOf(
                        BookmakerOddsRow("Odds API", 2.00, 2.00, 0.0)
                    )
                )
            )
        }
        return list
    }

    private fun executeGet(url: String): String {
        val request = Request.Builder().url(url).get().build()
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}: $body")
            }
            return body
        }
    }
}
