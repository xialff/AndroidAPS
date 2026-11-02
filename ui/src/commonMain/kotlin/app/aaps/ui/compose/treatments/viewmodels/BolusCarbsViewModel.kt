package app.aaps.ui.compose.treatments.viewmodels

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Sources
import app.aaps.core.interfaces.bolus.BatchAction
import app.aaps.core.interfaces.bolus.BatchExecutor
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.db.observeChanges
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.LTag
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.TextResolver
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventShowSnackbar
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.ui.clientcontrol.RecordRemoval
import app.aaps.core.ui.compose.SelectableListToolbar
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.ui.compose.treatments.MealLink
import app.aaps.ui.compose.treatments.viewmodels.TreatmentConstants.TREATMENT_HISTORY_DAYS
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for BolusCarbsScreen managing bolus, carbs, and calculator result state and business logic.
 */
@Stable
@Inject
class BolusCarbsViewModel(
    private val persistenceLayer: PersistenceLayer,
    private val profileFunction: ProfileFunction,
    val rh: TextResolver,
    val dateUtil: DateUtil,
    val decimalFormatter: DecimalFormatter,
    private val aapsLogger: AAPSLogger,
    private val rxBus: RxBus,
    batchExecutor: BatchExecutor,
    config: Config,
    private val preferences: Preferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(BolusCarbsUiState())
    val uiState: StateFlow<BolusCarbsUiState> = _uiState.asStateFlow()

    init {
        // 恢复上次保存的统计起始点（跨进程持久化）
        _uiState.update {
            it.copy(
                statsStartTimestamp = preferences.get(LongNonKey.TreatmentStatsStartTimestamp),
                statsStartLabel = preferences.get(StringNonKey.TreatmentStatsStartLabel)
            )
        }
        loadData()
        observeTreatmentChanges()
    }

    /**
     * Load meal links (boluses, carbs, and calculator results)
     */
    fun loadData() {
        viewModelScope.launch {
            // Only show loading on initial load, not on refreshes
            val currentState = uiState.value
            if (currentState.mealLinks.isEmpty()) {
                _uiState.update { it.copy(isLoading = true) }
            }

            try {
                val now = dateUtil.now()
                val millsToThePast = T.days(TREATMENT_HISTORY_DAYS).msecs()

                val boluses = if (currentState.showInvalidated) {
                    persistenceLayer.getBolusesFromTimeIncludingInvalid(now - millsToThePast, false)
                        .map { MealLink(bolus = it) }
                } else {
                    persistenceLayer.getBolusesFromTime(now - millsToThePast, false)
                        .map { MealLink(bolus = it) }
                }

                val carbs = if (currentState.showInvalidated) {
                    persistenceLayer.getCarbsFromTimeIncludingInvalid(now - millsToThePast, false)
                        .map { MealLink(carbs = it) }
                } else {
                    persistenceLayer.getCarbsFromTime(now - millsToThePast, false)
                        .map { MealLink(carbs = it) }
                }

                val calcs = if (currentState.showInvalidated) {
                    persistenceLayer.getBolusCalculatorResultsIncludingInvalidFromTime(now - millsToThePast, false)
                        .map { MealLink(bolusCalculatorResult = it) }
                } else {
                    persistenceLayer.getBolusCalculatorResultsFromTime(now - millsToThePast, false)
                        .map { MealLink(bolusCalculatorResult = it) }
                }

                val mealLinks = (boluses + carbs + calcs).sortedByDescending {
                    it.bolusCalculatorResult?.timestamp ?: it.bolus?.timestamp ?: it.carbs?.timestamp ?: 0L
                }

                // 胰岛素累计统计：从所选起始记录到现在，所有有效大剂量之和
                val startTs = uiState.value.statsStartTimestamp
                val statsTotal = if (startTs > 0L) {
                    persistenceLayer.getBolusesFromTime(startTs, false).sumOf { it.amount }
                } else 0.0

                _uiState.update {
                    it.copy(
                        mealLinks = mealLinks,
                        isLoading = false,
                        statsTotalInsulin = statsTotal
                    )
                }
            } catch (e: Exception) {
                aapsLogger.error(LTag.UI, "Failed to load bolus/carbs data", e)
                _uiState.update { it.copy(isLoading = false) }
                rxBus.send(EventShowSnackbar(e.message ?: "Unknown error loading bolus/carbs data", EventShowSnackbar.Type.Error))
            }
        }
    }

    /**
     * Subscribe to treatment change events using Flow
     * Observes Bolus, Carbs, and BolusCalculatorResult changes
     */
    @OptIn(FlowPreview::class)
    private fun observeTreatmentChanges() {
        merge(
            persistenceLayer.observeChanges<BS>(),
            persistenceLayer.observeChanges<CA>(),
            persistenceLayer.observeChanges<BCR>()
        )
            .debounce(1000L) // 1 second debounce
            .onEach { loadData() }
            .launchIn(viewModelScope)
    }

    /**
     * Toggle show/hide invalidated items
     */
    fun toggleInvalidated() {
        _uiState.update { it.copy(showInvalidated = !it.showInvalidated) }
        loadData()
    }

    /**
     * Enter selection mode with initial item selected
     */
    fun enterSelectionMode(item: MealLink) {
        _uiState.update {
            it.copy(
                isRemovingMode = true,
                selectedItems = setOf(item)
            )
        }
    }

    /**
     * Exit selection mode and clear selection
     */
    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                isRemovingMode = false,
                selectedItems = emptySet()
            )
        }
    }

    /**
     * Toggle selection of an item
     */
    fun toggleSelection(item: MealLink) {
        _uiState.update { state ->
            val newSelection = if (item in state.selectedItems) {
                state.selectedItems - item
            } else {
                state.selectedItems + item
            }
            state.copy(selectedItems = newSelection)
        }
    }

    /**
     * 把选中的治疗记录设为"胰岛素累计统计"的起始点（单击记录触发）。
     * 起始点持久化保存，退出重启后依然保留，直到选新的或清除。
     */
    fun setStatsStart(item: MealLink) {
        val ts = item.timestamp()
        if (ts <= 0L) return
        val label = dateUtil.dateAndTimeString(ts)
        preferences.put(LongNonKey.TreatmentStatsStartTimestamp, ts)
        preferences.put(StringNonKey.TreatmentStatsStartLabel, label)
        _uiState.update { it.copy(statsStartTimestamp = ts, statsStartLabel = label) }
        loadData()
    }

    /** 清除统计起始点 */
    fun clearStatsStart() {
        preferences.put(LongNonKey.TreatmentStatsStartTimestamp, 0L)
        preferences.put(StringNonKey.TreatmentStatsStartLabel, "")
        _uiState.update { it.copy(statsStartTimestamp = 0L, statsStartLabel = "", statsTotalInsulin = 0.0) }
        loadData()
    }

    /**
     * Get current profile
     */
    suspend fun getProfile() = profileFunction.getProfile()

    /** Removing the selected items: the master describes and removes them, on every role. */
    val removal = RecordRemoval(batchExecutor, rh, rxBus, config, viewModelScope, Sources.Treatments)

    /** Ask the master to describe removing the selected items; the screen then shows its confirmation. */
    fun requestDelete() {
        val selected = uiState.value.selectedItems
        removal.request(
            listOfNotNull(
                BatchAction.Remove.of(BatchAction.Remove.Kind.BOLUS, selected.mapNotNull { it.bolus }),
                BatchAction.Remove.of(BatchAction.Remove.Kind.CARBS, selected.mapNotNull { it.carbs }),
                BatchAction.Remove.of(BatchAction.Remove.Kind.BOLUS_CALCULATOR_RESULT, selected.mapNotNull { it.bolusCalculatorResult })
            )
        )
    }

    /** The master removed them. */
    fun onRemoved() {
        exitSelectionMode()
        loadData()
    }

    /**
     * Get toolbar configuration for current state
     */
    fun getToolbarConfig(onNavigateBack: () -> Unit, onDeleteClick: () -> Unit): ToolbarConfig {
        val state = uiState.value
        return SelectableListToolbar(
            isRemovingMode = state.isRemovingMode,
            selectedCount = state.selectedItems.size,
            onExitRemovingMode = { exitSelectionMode() },
            onNavigateBack = onNavigateBack,
            onDelete = {
                if (state.selectedItems.isNotEmpty()) {
                    onDeleteClick()
                }
            },
            rh = rh,
            showInvalidated = state.showInvalidated,
            onToggleInvalidated = { toggleInvalidated() }
        )
    }
}

/**
 * UI state for BolusCarbsScreen
 */
@Immutable
data class BolusCarbsUiState(
    val mealLinks: List<MealLink> = emptyList(),
    val isLoading: Boolean = true,
    val showInvalidated: Boolean = false,
    val isRemovingMode: Boolean = false,
    val selectedItems: Set<MealLink> = emptySet(),
    // 胰岛素累计统计（0 = 未设置起始点）
    val statsStartTimestamp: Long = 0L,
    val statsTotalInsulin: Double = 0.0,
    val statsStartLabel: String = "",
)
