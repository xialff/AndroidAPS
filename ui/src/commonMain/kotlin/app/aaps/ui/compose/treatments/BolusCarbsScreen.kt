package app.aaps.ui.compose.treatments

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.model.iobCalc
import app.aaps.core.data.time.T
import app.aaps.core.interfaces.InterfacesStrings
import app.aaps.core.interfaces.navigation.ElementType
import app.aaps.core.interfaces.profile.Profile
import app.aaps.core.ui.CoreUiStrings
import app.aaps.core.ui.compose.AapsCard
import app.aaps.core.ui.compose.AapsTheme
import app.aaps.core.ui.compose.LocalDateUtil
import app.aaps.core.ui.compose.StyledArg
import app.aaps.core.ui.compose.ToolbarConfig
import app.aaps.core.ui.compose.dialogs.RecordRemovalDialog
import app.aaps.core.ui.compose.icons.Ns
import app.aaps.core.ui.compose.icons.Pump
import app.aaps.core.ui.compose.navigation.color
import app.aaps.core.ui.compose.navigation.icon
import app.aaps.core.ui.compose.stringResource
import app.aaps.core.ui.compose.styledStringResource
import app.aaps.ui.compose.components.ContentContainer
import app.aaps.ui.compose.treatments.viewmodels.BolusCarbsViewModel
import app.aaps.ui.UiStrings

/**
 * Composable screen displaying boluses and carbs in a combined list.
 *
 * @param viewModel ViewModel managing state and business logic
 * @param setToolbarConfig Lambda to set toolbar configuration
 * @param onNavigateBack Lambda to handle back navigation
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun BolusCarbsScreen(
    viewModel: BolusCarbsViewModel,
    setToolbarConfig: (ToolbarConfig) -> Unit,
    onNavigateBack: () -> Unit = { }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Dialog state
    var showInfoBcr by remember { mutableStateOf<BCR?>(null) }

    val profile by produceState<Profile?>(null, uiState.mealLinks) { value = viewModel.getProfile() }

    // Update toolbar configuration whenever state changes
    LaunchedEffect(uiState.isRemovingMode, uiState.selectedItems.size, uiState.showInvalidated) {
        setToolbarConfig(
            viewModel.getToolbarConfig(
                onNavigateBack = onNavigateBack,
                onDeleteClick = { viewModel.requestDelete() }
            )
        )
    }

    // Wizard info dialog
    showInfoBcr?.let { bcr ->
        WizardInfoDialog(
            bcr = bcr,
            decimalFormatter = viewModel.decimalFormatter,
            onDismiss = { showInfoBcr = null }
        )
    }

    // Delete confirmation, written by the master
    RecordRemovalDialog(removal = viewModel.removal, onApplied = { viewModel.onRemoved() })

    AapsTheme {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 胰岛素累计统计栏：仅在设置了起始记录后显示
                if (uiState.statsStartTimestamp > 0L) {
                    InsulinStatsBar(
                        totalInsulin = uiState.statsTotalInsulin,
                        startLabel = uiState.statsStartLabel,
                        onClear = { viewModel.clearStatsStart() }
                    )
                }
                ContentContainer(
                    isLoading = uiState.isLoading,
                    isEmpty = uiState.mealLinks.isEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    val haptic = LocalHapticFeedback.current

                    TreatmentLazyColumn(
                        items = uiState.mealLinks,
                        getTimestamp = { it.timestamp() },
                        getItemKey = { "b${it.bolus?.id}_c${it.carbs?.id}_bcr${it.bolusCalculatorResult?.id}" },
                        rh = viewModel.rh,
                        itemContent = { ml ->
                            MealLinkItem(
                                mealLink = ml,
                                isRemovingMode = uiState.isRemovingMode,
                                isSelected = ml in uiState.selectedItems,
                                isStatsStart = uiState.statsStartTimestamp > 0L && ml.timestamp() == uiState.statsStartTimestamp,
                                onClick = {
                                    if (uiState.isRemovingMode && ml.isValid()) {
                                        // 删除模式：切换选择
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.toggleSelection(ml)
                                    } else if (!uiState.isRemovingMode) {
                                        // 单击记录：设为胰岛素累计统计的起始点
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        viewModel.setStatsStart(ml)
                                    }
                                },
                                onLongPress = {
                                    if (ml.isValid() && !uiState.isRemovingMode) {
                                        // Haptic feedback for selection mode entry
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        // Enter selection mode and select this item
                                        viewModel.enterSelectionMode(ml)
                                    }
                                },
                                onCalculatorClick = { bcr -> showInfoBcr = bcr },
                                profile = profile,
                                showInvalidated = uiState.showInvalidated
                            )
                        }
                    )
                }
            }
        }
    }
}

data class MealLink(
    val bolus: BS? = null,
    val carbs: CA? = null,
    val bolusCalculatorResult: BCR? = null
) {

    fun isValid(): Boolean {
        return (bolus?.isValid ?: true) && (carbs?.isValid ?: true) && (bolusCalculatorResult?.isValid ?: true)
    }

    /** 该条记录的基准时间戳（用于排序与统计起始点识别） */
    fun timestamp(): Long =
        bolusCalculatorResult?.timestamp ?: bolus?.timestamp ?: carbs?.timestamp ?: 0L
}

/**
 * 胰岛素累计统计栏：显示从所选起始记录到现在的胰岛素总量。
 * 起始记录点选后持久化保存，退出重启依然显示，直到点选新的或点此栏的清除按钮。
 */
@Composable
private fun InsulinStatsBar(
    totalInsulin: Double,
    startLabel: String,
    onClear: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(
                        UiStrings.stats_total_insulin,
                        stringResource(InterfacesStrings.format_insulin_units, totalInsulin)
                    ),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                if (startLabel.isNotEmpty()) {
                    Text(
                        text = stringResource(UiStrings.stats_start_label, startLabel),
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                }
            }
            IconButton(onClick = onClear) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = stringResource(UiStrings.stats_clear_start),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MealLinkItem(
    mealLink: MealLink,
    isRemovingMode: Boolean,
    isSelected: Boolean,
    isStatsStart: Boolean,
    onClick: () -> Unit,
    onLongPress: () -> Unit,
    onCalculatorClick: (BCR) -> Unit,
    profile: Profile?,
    showInvalidated: Boolean
) {
    val dateUtil = LocalDateUtil.current
    AapsCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 2.dp)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress,
                // Long press is the only way into remove mode; TalkBack says "double-tap and hold to Remove".
                onLongClickLabel = stringResource(CoreUiStrings.remove)
            ),
        selected = isSelected
    ) {
        Column(
            modifier = Modifier.padding(1.dp)
        ) {

            // 统计起始点徽标
            if (isStatsStart) {
                Text(
                    text = stringResource(UiStrings.stats_start_marker),
                    modifier = Modifier
                        .padding(start = 8.dp, top = 4.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(4.dp)
                        )
                        .padding(horizontal = 6.dp, vertical = 1.dp),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                )
            }

            // Bolus Calculator Result (Metadata)
            mealLink.bolusCalculatorResult?.let { bcr ->
                if (bcr.isValid || showInvalidated) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateUtil.timeString(bcr.timestamp),
                            fontSize = 14.sp
                        )

                        Box(modifier = Modifier.weight(1f))

                        IconButton(
                            onClick = { onCalculatorClick(bcr) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = ElementType.BOLUS_WIZARD.icon(),
                                contentDescription = stringResource(CoreUiStrings.boluswizard),
                                modifier = Modifier.size(21.dp),
                                tint = ElementType.BOLUS_WIZARD.color()
                            )
                        }

                        if (bcr.ids.nightscoutId != null) {
                            Icon(
                                imageVector = Ns,
                                contentDescription = stringResource(CoreUiStrings.ns),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp)
                            )
                        }

                        if (isRemovingMode && bcr.isValid) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onClick() },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Bolus
            mealLink.bolus?.let { bolus ->
                if (bolus.isValid || showInvalidated) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateUtil.timeString(bolus.timestamp),
                            fontSize = 14.sp,
                            color = if (bolus.timestamp > dateUtil.now()) Color(AapsTheme.generalColors.futureRecord.value) else MaterialTheme.colorScheme.onSurface
                        )

                        // Bolus amount with IOB
                        profile?.let { _ ->
                            val iob = bolus.iobCalc(dateUtil.now())
                            val bolusText = if (iob.iobContrib > 0.01) {
                                styledStringResource(
                                    CoreUiStrings.value_with_unit,
                                    StyledArg(stringResource(InterfacesStrings.format_insulin_units, bolus.amount)),
                                    StyledArg(
                                        stringResource(CoreUiStrings.value_in_parentheses, stringResource(InterfacesStrings.format_insulin_units, iob.iobContrib)),
                                        SpanStyle(color = Color(AapsTheme.generalColors.activeInsulinText.value))
                                    )
                                )
                            } else {
                                AnnotatedString(stringResource(InterfacesStrings.format_insulin_units, bolus.amount))
                            }

                            Text(
                                text = bolusText,
                                modifier = Modifier.padding(start = 10.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        } ?: run {
                            Text(
                                text = stringResource(InterfacesStrings.format_insulin_units, bolus.amount),
                                modifier = Modifier.padding(start = 10.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        // Insulin label (when different from active). The running profile owns the
                        // authoritative iCfg; with no profile there is nothing to compare against, so
                        // the label is shown rather than suppressed against a guessed "active".
                        val activeLabel = profile?.iCfg?.insulinLabel
                        if (bolus.iCfg.insulinLabel != activeLabel) {
                            Text(
                                text = bolus.iCfg.insulinLabel,
                                modifier = Modifier.padding(start = 4.dp),
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Box(modifier = Modifier.weight(1f))

                        // Bolus type — pre-migration text label (the legacy view showed "SMB" / "Meal Bolus" /
                        // "Prime/Fill" here; a carbs icon on a normal/correction bolus was misleading).
                        Text(
                            text = when (bolus.type) {
                                BS.Type.SMB     -> stringResource(CoreUiStrings.smb_shortname)
                                BS.Type.NORMAL  -> stringResource(CoreUiStrings.careportal_mealbolus)
                                BS.Type.PRIMING -> stringResource(CoreUiStrings.prime_fill)
                            },
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (bolus.ids.nightscoutId != null) {
                            Icon(
                                imageVector = Ns,
                                contentDescription = stringResource(CoreUiStrings.ns),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp)
                            )
                        }

                        if (bolus.ids.isPumpHistory()) {
                            Icon(
                                imageVector = Pump,
                                contentDescription = stringResource(CoreUiStrings.pump_history),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp)
                            )
                        }

                        if (!bolus.isValid) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(CoreUiStrings.invalid),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp),
                                tint = Color(AapsTheme.generalColors.invalidatedRecord.value)
                            )
                        }

                        if (isRemovingMode && bolus.isValid) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onClick() },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Carbs
            mealLink.carbs?.let { carbs ->
                if (carbs.isValid || showInvalidated) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = dateUtil.timeString(carbs.timestamp),
                            fontSize = 14.sp
                        )

                        Text(
                            text = styledStringResource(
                                InterfacesStrings.confirmation_line,
                                StyledArg(stringResource(InterfacesStrings.carbs)),
                                StyledArg(stringResource(InterfacesStrings.format_carbs, carbs.amount.toInt()), SpanStyle(fontWeight = FontWeight.Bold))
                            ),
                            modifier = Modifier.padding(start = 8.dp),
                            fontSize = 14.sp
                        )

                        if (carbs.duration > 0) {
                            Text(
                                text = stringResource(CoreUiStrings.format_mins, T.msecs(carbs.duration).mins().toInt()),
                                modifier = Modifier.padding(start = 8.dp),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        carbs.notes?.takeIf { it.isNotEmpty() }?.let { notes ->
                            Text(
                                text = notes,
                                modifier = Modifier.padding(start = 8.dp),
                                fontSize = 12.sp,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }

                        Box(modifier = Modifier.weight(1f))

                        if (carbs.ids.nightscoutId != null) {
                            Icon(
                                imageVector = Ns,
                                contentDescription = stringResource(CoreUiStrings.ns),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp)
                            )
                        }

                        if (carbs.ids.isPumpHistory()) {
                            Icon(
                                imageVector = Pump,
                                contentDescription = stringResource(CoreUiStrings.pump_history),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp)
                            )
                        }

                        if (!carbs.isValid) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(CoreUiStrings.invalid),
                                modifier = Modifier
                                    .size(21.dp)
                                    .padding(start = 5.dp),
                                tint = Color(AapsTheme.generalColors.invalidatedRecord.value)
                            )
                        }

                        if (isRemovingMode && carbs.isValid) {
                            Checkbox(
                                checked = isSelected,
                                onCheckedChange = { onClick() },
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }
                }
            }

            // Bolus notes (carbs notes are shown inline)
            mealLink.bolus?.let { bolus ->
                bolus.notes?.takeIf { it.isNotEmpty() && mealLink.carbs == null }?.let { notes ->
                    Text(
                        text = notes,
                        modifier = Modifier.padding(start = 12.dp, end = 8.dp, bottom = 3.dp),
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}
