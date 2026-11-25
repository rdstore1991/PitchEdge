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

            val rawScores = d.optJSONArray("score") ?: JSONArray()
            val enrichedScores = JSONArray()
            for (i in 0 until rawScores.length()) {
                val s = rawScores.getJSONObject(i)
                val runs = s.optInt("r", 0)
                val oversNotation = s.optDouble("o", 0.0)
                val wholeOvers = kotlin.math.floor(oversNotation)
                val balls = kotlin.math.round((oversNotation - wholeOvers) * 10.0)
                val trueOvers = wholeOvers + (balls / 6.0)
                val crr = if (trueOvers > 0.0) Math.round((runs / trueOvers) * 100.0) / 100.0 else 0.0
                enrichedScores.put(
                    JSONObject(s.toString()).apply {
                        put("crr", crr)
                    }
                )
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
                put("score", enrichedScores)
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

            val cacheKey = "pitchedge:oddsapi:odds_v2:$sportKey"
            var events = cache.get<JSONArray>(cacheKey)

            if (events == null) {
                val encodedSport = URLEncoder.encode(sportKey, "UTF-8")
                val encodedKey = URLEncoder.encode(BuildConfig.ODDS_API_KEY, "UTF-8")
                val multiMarketUrl = "$ODDS_BASE_URL/sports/$encodedSport/odds/?apiKey=$encodedKey&regions=uk,eu,au&markets=h2h,totals,spreads&oddsFormat=decimal"
                val rawBody = try {
                    executeGet(multiMarketUrl)
                } catch (e: IllegalStateException) {
                    if (e.message?.contains("HTTP 422") == true) {
                        val fallbackUrl = "$ODDS_BASE_URL/sports/$encodedSport/odds/?apiKey=$encodedKey&regions=uk,eu,au&markets=h2h&oddsFormat=decimal"
                        executeGet(fallbackUrl)
                    } else {
                        throw e
                    }
                }
                events = JSONArray(rawBody)
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

                val bestBackH2h = mutableMapOf<String, Pair<Double, String>>()
                val exchangeLayH2h = mutableMapOf<String, Pair<Double, String>>()
                // totals grouped by exact linePoint -> side ("Over"/"Under") -> (bestOdds, bookie)
                val totalsByPoint = mutableMapOf<Double, MutableMap<String, Pair<Double, String>>>()
                // spreads grouped by abs(point) -> "(name, signedPoint)" -> (bestOdds, bookie, teamName, signedPoint)
                val spreadsByAbs = mutableMapOf<Double, MutableMap<String, SpreadQuoteCandidate>>()

                var bookmakerCount = 0
                val bookmakers = event.optJSONArray("bookmakers") ?: JSONArray()

                for (bIdx in 0 until bookmakers.length()) {
                    val bookie = bookmakers.getJSONObject(bIdx)
                    val bookieTitle = bookie.optString("title")
                    val isExchange = bookie.optString("key").contains("betfair", ignoreCase = true) ||
                        bookie.optString("key").contains("smarkets", ignoreCase = true) ||
                        bookie.optString("key").contains("matchbook", ignoreCase = true)
                    val markets = bookie.optJSONArray("markets") ?: JSONArray()
                    for (mIdx in 0 until markets.length()) {
                        val mkt = markets.getJSONObject(mIdx)
                        val mktKey = mkt.optString("key")
                        val outcomes = mkt.optJSONArray("outcomes") ?: JSONArray()
                        when {
                            mktKey == "h2h" && outcomes.length() > 0 -> {
                                bookmakerCount++
                                for (oIdx in 0 until outcomes.length()) {
                                    val o = outcomes.getJSONObject(oIdx)
                                    val name = o.optString("name")
                                    val price = o.optDouble("price", 0.0)
                                    val prev = bestBackH2h[name]
                                    if (price > 0.0 && (prev == null || price > prev.first)) {
                                        bestBackH2h[name] = price to bookieTitle
                                    }
                                    if (isExchange && price > 0.0) {
                                        val layEst = Math.round((price + 0.02) * 100.0) / 100.0
                                        val prevLay = exchangeLayH2h[name]
                                        if (prevLay == null || layEst < prevLay.first) {
                                            exchangeLayH2h[name] = layEst to bookieTitle
                                        }
                                    }
                                }
                            }

                            mktKey == "h2h_lay" && outcomes.length() > 0 -> {
                                for (oIdx in 0 until outcomes.length()) {
                                    val o = outcomes.getJSONObject(oIdx)
                                    val name = o.optString("name")
                                    val price = o.optDouble("price", 0.0)
                                    val prev = exchangeLayH2h[name]
                                    if (price > 0.0 && (prev == null || price < prev.first)) {
                                        exchangeLayH2h[name] = price to bookieTitle
                                    }
                                }
                            }

                            mktKey == "totals" && outcomes.length() > 0 -> {
                                for (oIdx in 0 until outcomes.length()) {
                                    val o = outcomes.getJSONObject(oIdx)
                                    val name = o.optString("name")
                                    val price = o.optDouble("price", 0.0)
                                    val point = o.optDouble("point", Double.NaN)
                                    if (price > 0.0 && !point.isNaN()) {
                                        val sideMap = totalsByPoint.getOrPut(point) { mutableMapOf() }
                                        val prev = sideMap[name]
                                        if (prev == null || price > prev.first) {
                                            sideMap[name] = price to bookieTitle
                                        }
                                    }
                                }
                            }

                            mktKey == "spreads" && outcomes.length() > 0 -> {
                                for (oIdx in 0 until outcomes.length()) {
                                    val o = outcomes.getJSONObject(oIdx)
                                    val name = o.optString("name")
                                    val price = o.optDouble("price", 0.0)
                                    val point = o.optDouble("point", Double.NaN)
                                    if (price > 0.0 && !point.isNaN()) {
                                        val absLine = kotlin.math.abs(point)
                                        val lineMap = spreadsByAbs.getOrPut(absLine) { mutableMapOf() }
                                        val key = "$name|$point"
                                        val prev = lineMap[key]
                                        if (prev == null || price > prev.bestOdds) {
                                            lineMap[key] = SpreadQuoteCandidate(name, point, price, bookieTitle)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 1. Vig-strip H2H
                val rawH2hEntries = bestBackH2h.entries.map { (name, pair) ->
                    Triple(name, pair, 1.0 / pair.first)
                }
                val totalImpliedH2h = rawH2hEntries.sumOf { it.third }
                val overroundH2h = if (totalImpliedH2h > 0.0) {
                    Math.round((totalImpliedH2h - 1.0) * 10000.0) / 100.0
                } else 0.0

                val marketProbsArray = JSONArray()
                rawH2hEntries.forEach { (name, pair, rawImplied) ->
                    val rawPct = Math.round(rawImplied * 10000.0) / 100.0
                    val vigFreePct = if (totalImpliedH2h > 0.0) {
                        Math.round((rawImplied / totalImpliedH2h) * 10000.0) / 100.0
                    } else 0.0

                    marketProbsArray.put(
                        JSONObject().apply {
                            put("selection", name)
                            put("bestBackOdds", pair.first)
                            put("bestBookmaker", pair.second)
                            put("exchangeLayOdds", exchangeLayH2h[name]?.first ?: JSONObject.NULL)
                            put("rawImpliedPercent", rawPct)
                            put("trueVigFreeMarketPercent", vigFreePct)
                        }
                    )
                }

                // 2. Vig-strip Totals grouped by linePoint
                val totalsArray = JSONArray()
                totalsByPoint.toSortedMap().forEach { (linePoint, sideMap) ->
                    val entries = sideMap.entries.map { (side, pair) -> Triple(side, pair, 1.0 / pair.first) }
                    val sumRaw = entries.sumOf { it.third }
                    val overround = if (sumRaw > 0.0) Math.round((sumRaw - 1.0) * 10000.0) / 100.0 else 0.0
                    val outcomesArr = JSONArray()
                    entries.forEach { (side, pair, rawImp) ->
                        val rawPct = Math.round(rawImp * 10000.0) / 100.0
                        val vigFreePct = if (sumRaw > 0.0) Math.round((rawImp / sumRaw) * 10000.0) / 100.0 else 0.0
                        outcomesArr.put(
                            JSONObject().apply {
                                put("selection", "$side $linePoint")
                                put("side", side)
                                put("linePoint", linePoint)
                                put("bestBackOdds", pair.first)
                                put("bestBookmaker", pair.second)
                                put("rawImpliedPercent", rawPct)
                                put("trueVigFreeMarketPercent", vigFreePct)
                            }
                        )
                    }
                    totalsArray.put(
                        JSONObject().apply {
                            put("linePoint", linePoint)
                            put("marketOverroundPercent", overround)
                            put("outcomes", outcomesArr)
                        }
                    )
                }

                // 3. Vig-strip Spreads grouped by absolute spread margin
                val spreadsArray = JSONArray()
                spreadsByAbs.toSortedMap().forEach { (absSpread, lineMap) ->
                    val entries = lineMap.values.map { cand -> cand to (1.0 / cand.bestOdds) }
                    val sumRaw = entries.sumOf { it.second }
                    val overround = if (sumRaw > 0.0) Math.round((sumRaw - 1.0) * 10000.0) / 100.0 else 0.0
                    val outcomesArr = JSONArray()
                    entries.forEach { (cand, rawImp) ->
                        val rawPct = Math.round(rawImp * 10000.0) / 100.0
                        val vigFreePct = if (sumRaw > 0.0) Math.round((rawImp / sumRaw) * 10000.0) / 100.0 else 0.0
                        val signedStr = if (cand.signedPoint > 0) "+${cand.signedPoint}" else "${cand.signedPoint}"
                        outcomesArr.put(
                            JSONObject().apply {
                                put("selection", "${cand.teamName} $signedStr")
                                put("linePoint", cand.signedPoint)
                                put("bestBackOdds", cand.bestOdds)
                                put("bestBookmaker", cand.bookmaker)
                                put("rawImpliedPercent", rawPct)
                                put("trueVigFreeMarketPercent", vigFreePct)
                            }
                        )
                    }
                    spreadsArray.put(
                        JSONObject().apply {
                            put("absSpread", absSpread)
                            put("marketOverroundPercent", overround)
                            put("outcomes", outcomesArr)
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
                        put("marketOverroundPercent", overroundH2h)
                        put("marketProbabilities", marketProbsArray)
                        put("totalsMarkets", totalsArray)
                        put("spreadsMarkets", spreadsArray)
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

    suspend fun fetchCricApiSeriesListJson(searchQuery: String): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isCricApiConfigured()) {
                return@withContext JSONObject().apply {
                    put("error", "CRIC_API_KEY is not set. Add CRIC_API_KEY in the AI Studio Secrets panel.")
                }
            }
            val cacheKey = "pitchedge:cricapi:series:${searchQuery.lowercase(Locale.US)}"
            cache.get<JSONObject>(cacheKey)?.let { return@withContext it }

            val url = "$CRIC_BASE_URL/series?apikey=${
                URLEncoder.encode(BuildConfig.CRIC_API_KEY, "UTF-8")
            }&offset=0&search=${URLEncoder.encode(searchQuery, "UTF-8")}"
            val payload = JSONObject(executeGet(url))
            cache.set(cacheKey, payload, 300L)
            payload
        }

    suspend fun fetchCricApiSeriesPointsJson(seriesId: String): JSONObject =
        withContext(Dispatchers.IO) {
            if (!isCricApiConfigured()) {
                return@withContext JSONObject().apply {
                    put("error", "CRIC_API_KEY is not set. Add CRIC_API_KEY in the AI Studio Secrets panel.")
                }
            }
            val cacheKey = "pitchedge:cricapi:series_points:$seriesId"
            cache.get<JSONObject>(cacheKey)?.let { return@withContext it }

            val url = "$CRIC_BASE_URL/series_points?apikey=${
                URLEncoder.encode(BuildConfig.CRIC_API_KEY, "UTF-8")
            }&id=${URLEncoder.encode(seriesId, "UTF-8")}"
            val payload = JSONObject(executeGet(url))
            cache.set(cacheKey, payload, 120L)
            payload
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

private data class SpreadQuoteCandidate(
    val teamName: String,
    val signedPoint: Double,
    val bestOdds: Double,
    val bookmaker: String
)
