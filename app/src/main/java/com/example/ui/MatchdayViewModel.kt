package com.example.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.ai.AiAnalystService
import com.example.ai.AiStreamEvent
import com.example.data.MatchRepository
import com.example.model.AnalystChatMessage
import com.example.model.AnalyticalPick
import com.example.model.AppDestination
import com.example.model.EdgeFilter
import com.example.model.FixtureOverview
import com.example.model.FormatFilter
import com.example.model.GroundedIntelUiState
import com.example.model.MarketEdgeItem
import com.example.model.PhaseMatchupInfo
import com.example.model.PlayerLeverageNote
import com.example.model.PlayerSlot
import com.example.model.PreTossChecklist
import com.example.model.SportFilter
import com.example.model.StatusFilter
import com.example.model.TacticalPhase
import com.example.model.TeamSide
import com.example.model.TossScenario
import com.example.model.VenueScorePoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

private const val TAG = "MatchdayViewModel"

data class MatchdayUiState(
    val currentTab: AppDestination = AppDestination.MATCHDAY,
    val darkTheme: Boolean = true,
    val selectedSport: SportFilter = SportFilter.CRICKET,
    val selectedFormat: FormatFilter = FormatFilter.ALL,
    val selectedStatus: StatusFilter = StatusFilter.UPCOMING_TODAY,
    val allFixtures: List<FixtureOverview> = emptyList(),
    val selectedFixtureId: String = "ilt20_m6_dc_dv",
    val projectedFirstInnings: Int = 166,
    val projectedSecondInnings: Int = 154,
    val tossScenario: TossScenario = TossScenario.PRE_TOSS,
    val selectedXiTeam: TeamSide = TeamSide.DC,
    val edgeFilter: EdgeFilter = EdgeFilter.ALL,
    val selectedCalculatorMarketId: String = "fakhar_10_quicker",
    val customOdds: Double = 2.20,
    val unitSizeDollars: Double = 50.0,
    val kellyMultiplier: Double = 0.25,
    val selectedPhase: TacticalPhase = TacticalPhase.MIDDLE,
    val checklist: PreTossChecklist = PreTossChecklist(),
    val venueScores: List<VenueScorePoint> = emptyList(),
    val dcXi: List<PlayerSlot> = emptyList(),
    val dvXi: List<PlayerSlot> = emptyList(),
    val marketEdges: List<MarketEdgeItem> = emptyList(),
    val phaseMatchups: List<PhaseMatchupInfo> = emptyList(),
    val playerLeverages: List<PlayerLeverageNote> = emptyList(),
    val primarySelection: AnalyticalPick? = null,
    val secondaryLeans: List<AnalyticalPick> = emptyList(),
    val groundedIntelState: GroundedIntelUiState = GroundedIntelUiState.Idle,
    val isChatOpen: Boolean = false,
    val chatInput: String = "",
    val isChatStreaming: Boolean = false,
    val chatMessages: List<AnalystChatMessage> = listOf(
        AnalystChatMessage(
            id = "welcome_msg",
            isUser = false,
            text = "PitchEdge AI Analyst ready with Google Search Grounding. Ask about Fakhar vs Babar at Dubai, live toss/XI updates, session par runs, or custom Kelly bankroll sizing."
        )
    )
) {
    val filteredFixtures: List<FixtureOverview>
        get() {
            val bySport = allFixtures.filter { it.sport == selectedSport }
            val byFormat = if (selectedFormat == FormatFilter.ALL || selectedSport != SportFilter.CRICKET) {
                bySport
            } else {
                bySport.filter { it.format == selectedFormat }
            }
            val byStatus = when (selectedStatus) {
                StatusFilter.VALUE_PICKS -> byFormat.filter { it.hasPositiveEdge }
                StatusFilter.LIVE_NOW -> byFormat.filter { it.status == StatusFilter.LIVE_NOW }
                StatusFilter.UPCOMING_TODAY -> byFormat.filter { it.status == StatusFilter.UPCOMING_TODAY }
            }
            return byStatus.ifEmpty { byFormat.ifEmpty { bySport } }
        }

    val activeFixture: FixtureOverview
        get() = allFixtures.find { it.id == selectedFixtureId }
            ?: filteredFixtures.firstOrNull()
            ?: allFixtures.first()

    val isFeaturedDcDvMatch: Boolean
        get() = activeFixture.id == "ilt20_m6_dc_dv"

    val filteredEdges: List<MarketEdgeItem>
        get() = when (edgeFilter) {
            EdgeFilter.ALL -> marketEdges
            EdgeFilter.POSITIVE_EV -> marketEdges.filter { it.edgePct > 0.0 }
            EdgeFilter.PROPS -> marketEdges.filter { it.isProp }
        }

    val selectedCalculatorMarket: MarketEdgeItem?
        get() = marketEdges.find { it.id == selectedCalculatorMarketId } ?: marketEdges.firstOrNull()

    val selectedPhaseInfo: PhaseMatchupInfo?
        get() = phaseMatchups.find { it.phase == selectedPhase } ?: phaseMatchups.firstOrNull()

    val isFakharQualified: Boolean get() = checklist.fakharBabarOpeningConfirmed
    val isDcMlQualified: Boolean get() = checklist.powellOrShanakaInDcXi || checklist.dcWonTossAndBowls
    val isHoldenQualified: Boolean get() = checklist.holdenInStartingXi

    val totalQualifiedUnits: Double
        get() {
            var total = 0.0
            if (isFakharQualified) total += 2.0
            if (isDcMlQualified) total += 1.0
            if (isHoldenQualified) total += 0.75
            return total
        }
}

class MatchdayViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = MatchRepository(application.applicationContext)
    private val aiService = AiAnalystService()

    private val _uiState = MutableStateFlow(
        MatchdayUiState(
            allFixtures = repository.fixturesSlate,
            venueScores = repository.season5VenueScores,
            dcXi = repository.dcProjectedXi,
            dvXi = repository.dvProjectedXi,
            marketEdges = repository.marketEdgeMatrix,
            phaseMatchups = repository.phaseMatchups,
            playerLeverages = repository.playerLeverages,
            primarySelection = repository.primarySelection,
            secondaryLeans = repository.secondaryLeans
        )
    )
    val uiState: StateFlow<MatchdayUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            repository.userPrefsFlow.collect { prefs ->
                _uiState.update { state ->
                    state.copy(
                        darkTheme = prefs.darkTheme,
                        tossScenario = prefs.tossScenario,
                        unitSizeDollars = prefs.unitSizeDollars,
                        kellyMultiplier = prefs.kellyMultiplier,
                        checklist = prefs.checklist
                    )
                }
            }
        }
    }

    fun selectTab(tab: AppDestination) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun toggleTheme() {
        val next = !_uiState.value.darkTheme
        _uiState.update { it.copy(darkTheme = next) }
        viewModelScope.launch { repository.setDarkTheme(next) }
    }

    fun selectSport(sport: SportFilter) {
        _uiState.update { state ->
            val updated = state.copy(selectedSport = sport, groundedIntelState = GroundedIntelUiState.Idle)
            val firstMatch = updated.filteredFixtures.firstOrNull()
            if (firstMatch != null) {
                updated.copy(
                    selectedFixtureId = firstMatch.id,
                    projectedFirstInnings = firstMatch.defaultFirstInnScore,
                    projectedSecondInnings = firstMatch.defaultSecondInnScore
                )
            } else updated
        }
    }

    fun selectFormat(format: FormatFilter) {
        _uiState.update { state ->
            val updated = state.copy(selectedFormat = format)
            val firstMatch = updated.filteredFixtures.firstOrNull()
            if (firstMatch != null) {
                updated.copy(
                    selectedFixtureId = firstMatch.id,
                    projectedFirstInnings = firstMatch.defaultFirstInnScore,
                    projectedSecondInnings = firstMatch.defaultSecondInnScore
                )
            } else updated
        }
    }

    fun selectStatus(status: StatusFilter) {
        _uiState.update { state ->
            val updated = state.copy(selectedStatus = status)
            val firstMatch = updated.filteredFixtures.firstOrNull()
            if (firstMatch != null) {
                updated.copy(
                    selectedFixtureId = firstMatch.id,
                    projectedFirstInnings = firstMatch.defaultFirstInnScore,
                    projectedSecondInnings = firstMatch.defaultSecondInnScore
                )
            } else updated
        }
    }

    fun selectFixture(fixtureId: String) {
        _uiState.update { state ->
            val fixture = state.allFixtures.find { it.id == fixtureId } ?: return@update state
            state.copy(
                selectedFixtureId = fixture.id,
                projectedFirstInnings = fixture.defaultFirstInnScore,
                projectedSecondInnings = fixture.defaultSecondInnScore,
                groundedIntelState = GroundedIntelUiState.Idle
            )
        }
    }

    fun updateProjectedFirstInnings(score: Int) {
        _uiState.update { it.copy(projectedFirstInnings = score) }
    }

    fun updateProjectedSecondInnings(score: Int) {
        _uiState.update { it.copy(projectedSecondInnings = score) }
    }

    fun selectTossScenario(scenario: TossScenario) {
        _uiState.update { it.copy(tossScenario = scenario) }
        viewModelScope.launch { repository.setTossScenario(scenario) }
    }

    fun selectXiTeam(team: TeamSide) {
        _uiState.update { it.copy(selectedXiTeam = team) }
    }

    fun selectEdgeFilter(filter: EdgeFilter) {
        _uiState.update { it.copy(edgeFilter = filter) }
    }

    fun selectCalculatorMarket(marketId: String) {
        val market = _uiState.value.marketEdges.find { it.id == marketId }
        _uiState.update {
            it.copy(
                selectedCalculatorMarketId = marketId,
                customOdds = market?.bestOdds ?: it.customOdds
            )
        }
    }

    fun adjustCustomOdds(delta: Double) {
        _uiState.update { state ->
            val updated = (state.customOdds + delta).coerceIn(1.10, 10.00)
            state.copy(customOdds = (Math.round(updated * 100.0) / 100.0))
        }
    }

    fun updateUnitSize(unitDollars: Double) {
        val clamped = unitDollars.coerceIn(10.0, 1000.0)
        _uiState.update { it.copy(unitSizeDollars = clamped) }
        viewModelScope.launch { repository.setUnitSize(clamped) }
    }

    fun updateKellyMultiplier(mult: Double) {
        _uiState.update { it.copy(kellyMultiplier = mult) }
        viewModelScope.launch { repository.setKellyMultiplier(mult) }
    }

    fun selectPhase(phase: TacticalPhase) {
        _uiState.update { it.copy(selectedPhase = phase) }
    }

    fun toggleFakharBabarOpening() {
        updateChecklist { it.copy(fakharBabarOpeningConfirmed = !it.fakharBabarOpeningConfirmed) }
    }

    fun togglePowellOrShanaka() {
        updateChecklist { it.copy(powellOrShanakaInDcXi = !it.powellOrShanakaInDcXi) }
    }

    fun toggleDcWonTossBowls() {
        updateChecklist { it.copy(dcWonTossAndBowls = !it.dcWonTossAndBowls) }
    }

    fun toggleHoldenStartingXi() {
        updateChecklist { it.copy(holdenInStartingXi = !it.holdenInStartingXi) }
    }

    private fun updateChecklist(transform: (PreTossChecklist) -> PreTossChecklist) {
        val updated = transform(_uiState.value.checklist)
        _uiState.update { it.copy(checklist = updated) }
        viewModelScope.launch { repository.updateChecklist(updated) }
    }

    fun refreshLiveGroundedIntel() {
        val fixture = _uiState.value.activeFixture
        val prompt = buildString {
            append("Search the web for the latest live updates on ")
            append("${fixture.homeName} vs ${fixture.awayName} (${fixture.leagueTitle}) ")
            append("today (Nov 25, 2026). ")
            append("Report: 1) Current live score or toss/playing XI confirmation, ")
            append("2) Any late injury/impact-sub news or weather/dew conditions at ${fixture.venue}, ")
            append("3) Current consensus decimal odds and whether our top angle (${fixture.bestPickTitle}) remains valid. ")
            append("Keep it concise in 4 crisp bullet points.")
        }

        viewModelScope.launch {
            _uiState.update { it.copy(groundedIntelState = GroundedIntelUiState.Loading) }
            try {
                val result = aiService.fetchGroundedIntel(prompt)
                _uiState.update {
                    it.copy(
                        groundedIntelState = GroundedIntelUiState.Success(
                            queryLabel = "${fixture.homeName} vs ${fixture.awayName}",
                            summary = result.summary,
                            searchQueries = result.searchQueries,
                            sources = result.sources
                        )
                    )
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update {
                    it.copy(
                        groundedIntelState = GroundedIntelUiState.Error(
                            "Google Search grounding timed out after 60s. Tap Retry to query again."
                        )
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Grounded intel failed", e)
                _uiState.update {
                    it.copy(
                        groundedIntelState = GroundedIntelUiState.Error(
                            e.localizedMessage ?: "Unable to fetch live grounded intel."
                        )
                    )
                }
            }
        }
    }

    fun setChatOpen(open: Boolean) {
        _uiState.update { it.copy(isChatOpen = open) }
    }

    fun updateChatInput(text: String) {
        _uiState.update { it.copy(chatInput = text) }
    }

    fun sendAnalystQuestion(customPrompt: String? = null) {
        val prompt = (customPrompt ?: _uiState.value.chatInput).trim()
        if (prompt.isBlank() || _uiState.value.isChatStreaming) return

        val stateSnapshot = _uiState.value
        val fixture = stateSnapshot.activeFixture
        val userMsg = AnalystChatMessage(
            id = UUID.randomUUID().toString(),
            isUser = true,
            text = prompt
        )
        val assistantMsgId = UUID.randomUUID().toString()
        val streamingPlaceholder = AnalystChatMessage(
            id = assistantMsgId,
            isUser = false,
            text = "",
            isStreaming = true
        )

        val historyPairs = stateSnapshot.chatMessages
            .filter { !it.isError && it.text.isNotBlank() }
            .map { it.isUser to it.text }

        val fixtureContext = buildString {
            appendLine("Sport: ${fixture.sport.label} | Format: ${fixture.format.label} | League: ${fixture.leagueTitle}")
            appendLine("Fixture: ${fixture.homeName} (${fixture.homeRecord}) vs ${fixture.awayName} (${fixture.awayRecord})")
            appendLine("Status: ${fixture.liveScoreOrTimer} | ${fixture.oversOrPeriod}")
            appendLine("Venue: ${fixture.venue} | Weather: ${fixture.weatherLine}")
            appendLine("Toss/Scenario: ${stateSnapshot.tossScenario.title} (${stateSnapshot.tossScenario.parRange})")
            appendLine("Win Prob Model: ${fixture.homeCode} ${fixture.homeModelPct}% vs ${fixture.awayCode} ${fixture.awayModelPct}% | Market: ${fixture.marketHomePct}% / ${fixture.marketAwayPct}%")
            appendLine("Projected Sliders: 1st Inn ${stateSnapshot.projectedFirstInnings}, 2nd Inn ${stateSnapshot.projectedSecondInnings} ${fixture.scoreUnitLabel}")
            appendLine("Top Pick: ${fixture.bestPickTitle} @ ${fixture.bestPickMarketOdds} (Fair ${fixture.bestPickFairOdds}, ${fixture.bestPickUnits}u)")
            appendLine("User Bankroll Unit Size: $${stateSnapshot.unitSizeDollars.toInt()} | Kelly Tier: ${stateSnapshot.kellyMultiplier}x")
            if (stateSnapshot.isFeaturedDcDvMatch) {
                appendLine("Key Edges: Fakhar 10 Runs Quicker @ 2.20 (+6.5% edge, 52% model vs 45.5% implied); Holden DC Top Bat @ 4.33 (+7.9% edge, Impact-Sub risk); DC ML @ 2.20 (+1.1% / +2.3% EV); Skip DV @ 1.78 (-1.1% edge).")
                appendLine("Key Players: Phil Salt out; monitor Powell/Shanaka; Babar 86(51) in M4; Hassan Khan 3.25 econ; Chameera 4-0-25-2; Noor 2/40; Shadab 3/18; Tom Curran 4-0-21-2; Khuzaima 3/31.")
            }
        }

        _uiState.update {
            it.copy(
                chatInput = if (customPrompt == null) "" else it.chatInput,
                isChatStreaming = true,
                isChatOpen = true,
                chatMessages = it.chatMessages + userMsg + streamingPlaceholder
            )
        }

        viewModelScope.launch {
            try {
                aiService.streamAnalystReply(
                    fixtureContext = fixtureContext,
                    history = historyPairs,
                    userPrompt = prompt
                ).collect { event ->
                    when (event) {
                        is AiStreamEvent.Chunk -> {
                            _uiState.update { s ->
                                s.copy(
                                    chatMessages = s.chatMessages.map { msg ->
                                        if (msg.id == assistantMsgId) {
                                            msg.copy(
                                                text = event.accumulatedText,
                                                isStreaming = true,
                                                searchQueries = event.searchQueries,
                                                sources = event.sources
                                            )
                                        } else msg
                                    }
                                )
                            }
                        }

                        is AiStreamEvent.Completed -> {
                            _uiState.update { s ->
                                s.copy(
                                    isChatStreaming = false,
                                    chatMessages = s.chatMessages.map { msg ->
                                        if (msg.id == assistantMsgId) {
                                            msg.copy(
                                                text = event.fullText,
                                                isStreaming = false,
                                                searchQueries = event.searchQueries,
                                                sources = event.sources
                                            )
                                        } else msg
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                _uiState.update { s ->
                    s.copy(
                        isChatStreaming = false,
                        chatMessages = s.chatMessages.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    text = "Request timed out after 60 seconds. Try a shorter query or tap retry.",
                                    isStreaming = false,
                                    isError = true
                                )
                            } else msg
                        }
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Analyst chat failed", e)
                _uiState.update { s ->
                    s.copy(
                        isChatStreaming = false,
                        chatMessages = s.chatMessages.map { msg ->
                            if (msg.id == assistantMsgId) {
                                msg.copy(
                                    text = e.localizedMessage ?: "Analyst request failed.",
                                    isStreaming = false,
                                    isError = true
                                )
                            } else msg
                        }
                    )
                }
            }
        }
    }
}
