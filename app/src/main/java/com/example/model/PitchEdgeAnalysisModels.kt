package com.example.model

import org.json.JSONArray
import org.json.JSONObject

data class PitchEdgeMarketQuote(
    val marketType: String, // "h2h", "totals", "spreads"
    val selection: String,
    val linePoint: Double? = null,
    val bestBackOdds: Double? = null,
    val exchangeLayOdds: Double? = null,
    val bestBookmaker: String = "Unlisted",
    val marketOverroundPercent: Double? = null,
    val trueMarketImpliedPercent: Double? = null,
    val pitchEdgeModelPercent: Double = 50.0,
    val fairValueOdds: Double = 2.0,
    val edgePercent: Double? = null
)

data class PitchEdgeMicroFactors(
    val eveningDewFactor: String = "",
    val powerplayEdge: String = "",
    val middleOversEdge: String = "",
    val deathOversEdge: String = "",
    val keyPlayerMatchup: String = "",
    val marketLagFlag: Boolean = false,
    val marketLagReason: String = ""
)

data class PitchEdgeProjections(
    val projectedFirstInningsRange: String = "",
    val projectedSecondInningsRange: String = "",
    val projectedMatchTotalRange: String = ""
)

data class PitchEdgeRecommendation(
    val action: String = "NO_BET_PASS", // "BET" or "NO_BET_PASS"
    val topMarketType: String = "h2h",
    val topSelection: String = "",
    val bestBookmaker: String = "Unlisted",
    val marketOdds: Double? = null,
    val fairValueOdds: Double = 2.0,
    val edgePercent: Double = 0.0,
    val unitStake: Int = 0, // 1..5 for BET, 0 for NO_BET_PASS
    val rationale: String = "",
    val invalidationTrigger: String = ""
)

data class PitchEdgeAnalysisResponse(
    val fixtureTitle: String = "",
    val competition: String = "",
    val matchStatus: String = "UPCOMING", // LIVE, UPCOMING, COMPLETED
    val scoreTelemetry: String = "",
    val venue: String = "",
    val tossOutcome: String = "Pending",
    val h2hMarkets: List<PitchEdgeMarketQuote> = emptyList(),
    val totalsMarkets: List<PitchEdgeMarketQuote> = emptyList(),
    val spreadsMarkets: List<PitchEdgeMarketQuote> = emptyList(),
    val projections: PitchEdgeProjections = PitchEdgeProjections(),
    val microFactors: PitchEdgeMicroFactors = PitchEdgeMicroFactors(),
    val recommendation: PitchEdgeRecommendation = PitchEdgeRecommendation()
) {
    val allMarkets: List<PitchEdgeMarketQuote>
        get() = h2hMarkets + totalsMarkets + spreadsMarkets

    companion object {
        fun fromJson(jsonString: String): PitchEdgeAnalysisResponse {
            val root = JSONObject(jsonString)

            fun parseNullableDouble(obj: JSONObject, key: String): Double? {
                if (!obj.has(key) || obj.isNull(key)) return null
                val raw = obj.optDouble(key, Double.NaN)
                return if (raw.isNaN()) null else raw
            }

            fun parseQuotes(arr: JSONArray?, defaultMarketType: String): List<PitchEdgeMarketQuote> {
                if (arr == null) return emptyList()
                return buildList {
                    for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        add(
                            PitchEdgeMarketQuote(
                                marketType = item.optString("marketType", defaultMarketType),
                                selection = item.optString("selection"),
                                linePoint = parseNullableDouble(item, "linePoint"),
                                bestBackOdds = parseNullableDouble(item, "bestBackOdds"),
                                exchangeLayOdds = parseNullableDouble(item, "exchangeLayOdds"),
                                bestBookmaker = item.optString("bestBookmaker", "Unlisted").ifBlank { "Unlisted" },
                                marketOverroundPercent = parseNullableDouble(item, "marketOverroundPercent"),
                                trueMarketImpliedPercent = parseNullableDouble(item, "trueMarketImpliedPercent"),
                                pitchEdgeModelPercent = item.optDouble("pitchEdgeModelPercent", 50.0),
                                fairValueOdds = item.optDouble("fairValueOdds", 2.0),
                                edgePercent = parseNullableDouble(item, "edgePercent")
                            )
                        )
                    }
                }
            }

            val projObj = root.optJSONObject("projections") ?: JSONObject()
            val microObj = root.optJSONObject("microFactors") ?: JSONObject()
            val recObj = root.optJSONObject("recommendation") ?: JSONObject()

            val rawAction = recObj.optString("action", "NO_BET_PASS")
            val rawEdge = recObj.optDouble("edgePercent", 0.0)
            val rawOdds = parseNullableDouble(recObj, "marketOdds")
            val enforcedAction = if (rawAction == "BET" && rawEdge >= 3.0 && rawOdds != null) "BET" else "NO_BET_PASS"
            val enforcedUnits = if (enforcedAction == "BET") {
                recObj.optInt("unitStake", 1).coerceIn(1, 5)
            } else {
                0
            }

            return PitchEdgeAnalysisResponse(
                fixtureTitle = root.optString("fixtureTitle"),
                competition = root.optString("competition"),
                matchStatus = root.optString("matchStatus", "UPCOMING"),
                scoreTelemetry = root.optString("scoreTelemetry"),
                venue = root.optString("venue"),
                tossOutcome = root.optString("tossOutcome", "Pending"),
                h2hMarkets = parseQuotes(root.optJSONArray("h2hMarkets"), "h2h"),
                totalsMarkets = parseQuotes(root.optJSONArray("totalsMarkets"), "totals"),
                spreadsMarkets = parseQuotes(root.optJSONArray("spreadsMarkets"), "spreads"),
                projections = PitchEdgeProjections(
                    projectedFirstInningsRange = projObj.optString("projectedFirstInningsRange"),
                    projectedSecondInningsRange = projObj.optString("projectedSecondInningsRange"),
                    projectedMatchTotalRange = projObj.optString("projectedMatchTotalRange")
                ),
                microFactors = PitchEdgeMicroFactors(
                    eveningDewFactor = microObj.optString("eveningDewFactor"),
                    powerplayEdge = microObj.optString("powerplayEdge"),
                    middleOversEdge = microObj.optString("middleOversEdge"),
                    deathOversEdge = microObj.optString("deathOversEdge"),
                    keyPlayerMatchup = microObj.optString("keyPlayerMatchup"),
                    marketLagFlag = microObj.optBoolean("marketLagFlag", false),
                    marketLagReason = microObj.optString("marketLagReason")
                ),
                recommendation = PitchEdgeRecommendation(
                    action = enforcedAction,
                    topMarketType = recObj.optString("topMarketType", "h2h"),
                    topSelection = recObj.optString("topSelection"),
                    bestBookmaker = recObj.optString("bestBookmaker", "Unlisted").ifBlank { "Unlisted" },
                    marketOdds = rawOdds,
                    fairValueOdds = recObj.optDouble("fairValueOdds", 2.0),
                    edgePercent = rawEdge,
                    unitStake = enforcedUnits,
                    rationale = recObj.optString("rationale"),
                    invalidationTrigger = recObj.optString("invalidationTrigger")
                )
            )
        }
    }
}
