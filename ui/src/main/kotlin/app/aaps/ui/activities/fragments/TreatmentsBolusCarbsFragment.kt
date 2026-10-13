package app.aaps.ui.activities.fragments

import android.annotation.SuppressLint
import android.graphics.Paint
import android.os.Bundle
import android.util.SparseArray
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import androidx.core.util.forEach
import androidx.core.view.MenuProvider
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import app.aaps.core.data.model.BCR
import app.aaps.core.data.model.BS
import app.aaps.core.data.model.CA
import app.aaps.core.data.time.T
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.rx.AapsSchedulers
import app.aaps.core.interfaces.rx.bus.RxBus
import app.aaps.core.interfaces.rx.events.EventTreatmentChange
import app.aaps.core.interfaces.utils.DateUtil
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.LongNonKey
import app.aaps.core.keys.StringNonKey
import app.aaps.core.keys.interfaces.Preferences
import app.aaps.core.objects.extensions.iobCalc
import app.aaps.core.objects.ui.ActionModeHelper
import app.aaps.core.ui.dialogs.OKDialog
import app.aaps.core.ui.extensions.toVisibility
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.ui.R
import app.aaps.ui.databinding.TreatmentsBolusCarbsFragmentBinding
import app.aaps.ui.databinding.TreatmentsBolusCarbsItemBinding
import app.aaps.ui.dialogs.WizardInfoDialog
import com.google.gson.Gson
import dagger.android.support.DaggerFragment
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.kotlin.plusAssign
import java.util.concurrent.TimeUnit
import javax.inject.Inject

class TreatmentsBolusCarbsFragment : DaggerFragment(), MenuProvider {

    @Inject lateinit var rxBus: RxBus
    @Inject lateinit var aapsLogger: AAPSLogger
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var fabricPrivacy: FabricPrivacy
    @Inject lateinit var profileFunction: ProfileFunction
    @Inject lateinit var dateUtil: DateUtil
    @Inject lateinit var config: Config
    @Inject lateinit var aapsSchedulers: AapsSchedulers
    @Inject lateinit var uel: UserEntryLogger
    @Inject lateinit var persistenceLayer: PersistenceLayer
    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var preferences: Preferences

    private var _binding: TreatmentsBolusCarbsFragmentBinding? = null

    // This property is only valid between onCreateView and onDestroyView.
    private val binding get() = _binding!!
    private var menu: Menu? = null

    /**
     * 统计功能：所选起始记录的时间戳（0 = 未设置）。
     * 通过 preferences 持久化，退出 AAPS 重新进入后依然保留，
     * 直到用户选择新的起始记录或手动清除。
     */
    private var statsStartTimestamp: Long = 0L

    class MealLink(
        val bolus: BS? = null,
        val carbs: CA? = null,
        val bolusCalculatorResult: BCR? = null
    )

    private val disposable = CompositeDisposable()
    private lateinit var actionHelper: ActionModeHelper<MealLink>
    private val millsToThePast = T.days(30).msecs()
    private var showInvalidated = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View =
        TreatmentsBolusCarbsFragmentBinding.inflate(inflater, container, false).also { _binding = it }.root

    @SuppressLint("NotifyDataSetChanged")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        actionHelper = ActionModeHelper(rh, activity, this)
        actionHelper.setUpdateListHandler { binding.recyclerview.adapter?.notifyDataSetChanged() }
        actionHelper.setOnRemoveHandler { removeSelected(it) }
        binding.recyclerview.setHasFixedSize(true)
        binding.recyclerview.layoutManager = LinearLayoutManager(view.context)
        binding.recyclerview.emptyView = binding.noRecordsText
        binding.recyclerview.loadingView = binding.progressBar

        // 统计栏：恢复上次保存的起始点（跨进程持久化）
        statsStartTimestamp = preferences.get(LongNonKey.TreatmentStatsStartTimestamp)
        binding.statsClear.setOnClickListener { clearStatsStart() }

        requireActivity().addMenuProvider(this, viewLifecycleOwner, Lifecycle.State.RESUMED)
    }

    // ------------------------------------------------------------------
    // 胰岛素累计统计
    // ------------------------------------------------------------------

    /**
     * 计算从起始记录到现在的胰岛素总量，并刷新统计栏。
     * 统计范围：所有有效的大剂量记录（含 SMB），使用与列表一致的 30 天查询窗口。
     */
    private fun updateStatsDisplay() {
        val stats = binding.statsLayout ?: return
        if (statsStartTimestamp <= 0L) {
            stats.visibility = View.GONE
            return
        }
        stats.visibility = View.VISIBLE

        val now = System.currentTimeMillis()
        val from = statsStartTimestamp.coerceAtLeast(now - millsToThePast)

        disposable += persistenceLayer
            .getBolusesFromTime(from, true)
            .observeOn(aapsSchedulers.main)
            .subscribe({ list ->
                _binding ?: return@subscribe
                val total = list.sumOf { it.amount }
                binding.statsTotal.text = rh.gs(R.string.stats_total_insulin, rh.gs(app.aaps.core.ui.R.string.format_insulin_units, total))
                val label = preferences.get(StringNonKey.TreatmentStatsStartLabel)
                binding.statsStartLabel.text =
                    if (label.isNotEmpty()) rh.gs(R.string.stats_start_label, label)
                    else rh.gs(R.string.stats_start_label, dateUtil.dateAndTimeString(statsStartTimestamp))
            }, fabricPrivacy::logException)
    }

    /** 把某条治疗记录设为统计起始点，并持久化 */
    private fun setStatsStart(timestamp: Long, description: String) {
        statsStartTimestamp = timestamp
        preferences.put(LongNonKey.TreatmentStatsStartTimestamp, timestamp)
        preferences.put(StringNonKey.TreatmentStatsStartLabel, dateUtil.dateAndTimeString(timestamp) + description)
        ToastUtils.infoToast(context, R.string.stats_start_set)
        updateStatsDisplay()
        binding.recyclerview.adapter?.notifyDataSetChanged()
    }

    /** 清除统计起始点 */
    private fun clearStatsStart() {
        statsStartTimestamp = 0L
        preferences.put(LongNonKey.TreatmentStatsStartTimestamp, 0L)
        preferences.put(StringNonKey.TreatmentStatsStartLabel, "")
        ToastUtils.infoToast(context, R.string.stats_start_cleared)
        binding.statsLayout.visibility = View.GONE
        binding.recyclerview.adapter?.notifyDataSetChanged()
    }

    private fun bolusMealLinksWithInvalid(now: Long) = persistenceLayer
        .getBolusesFromTimeIncludingInvalid(now - millsToThePast, false)
        .map { bolus -> bolus.map { MealLink(bolus = it) } }

    private fun carbsMealLinksWithInvalid(now: Long) = persistenceLayer
        .getCarbsFromTimeIncludingInvalid(now - millsToThePast, false)
        .map { carb -> carb.map { MealLink(carbs = it) } }

    private fun calcResultMealLinksWithInvalid(now: Long) = persistenceLayer
        .getBolusCalculatorResultsIncludingInvalidFromTime(now - millsToThePast, false)
        .map { calc -> calc.map { MealLink(bolusCalculatorResult = it) } }

    private fun bolusMealLinks(now: Long) = persistenceLayer
        .getBolusesFromTime(now - millsToThePast, false)
        .map { bolus -> bolus.map { MealLink(bolus = it) } }

    private fun carbsMealLinks(now: Long) = persistenceLayer
        .getCarbsFromTime(now - millsToThePast, false)
        .map { carb -> carb.map { MealLink(carbs = it) } }

    private fun calcResultMealLinks(now: Long) = persistenceLayer
        .getBolusCalculatorResultsFromTime(now - millsToThePast, false)
        .map { calc -> calc.map { MealLink(bolusCalculatorResult = it) } }

    private fun swapAdapter() {
        val now = System.currentTimeMillis()
        binding.recyclerview.isLoading = true
        disposable +=
            if (showInvalidated)
                carbsMealLinksWithInvalid(now)
                    .zipWith(bolusMealLinksWithInvalid(now)) { first, second -> first + second }
                    .zipWith(calcResultMealLinksWithInvalid(now)) { first, second -> first + second }
                    .map { ml ->
                        ml.sortedByDescending {
                            it.carbs?.timestamp ?: it.bolus?.timestamp
                            ?: it.bolusCalculatorResult?.timestamp
                        }
                    }
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list ->
                        binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true)
                        updateStatsDisplay()
                    }
            else
                carbsMealLinks(now)
                    .zipWith(bolusMealLinks(now)) { first, second -> first + second }
                    .zipWith(calcResultMealLinks(now)) { first, second -> first + second }
                    .map { ml ->
                        ml.sortedByDescending {
                            it.carbs?.timestamp ?: it.bolus?.timestamp
                            ?: it.bolusCalculatorResult?.timestamp
                        }
                    }
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list ->
                        binding.recyclerview.swapAdapter(RecyclerViewAdapter(list), true)
                        updateStatsDisplay()
                    }

    }

    @Synchronized
    override fun onResume() {
        super.onResume()
        swapAdapter()
        disposable += rxBus
            .toObservable(EventTreatmentChange::class.java)
            .observeOn(aapsSchedulers.main)
            .debounce(1L, TimeUnit.SECONDS)
            .subscribe({ swapAdapter() }, fabricPrivacy::logException)
    }

    @Synchronized
    override fun onPause() {
        super.onPause()
        actionHelper.finish()
        disposable.clear()
    }

    @Synchronized
    override fun onDestroyView() {
        super.onDestroyView()
        binding.recyclerview.adapter = null // avoid leaks
        _binding = null
    }

    private fun timestamp(ml: MealLink): Long = ml.bolusCalculatorResult?.timestamp ?: ml.bolus?.timestamp ?: ml.carbs?.timestamp ?: 0L

    inner class RecyclerViewAdapter internal constructor(private var mealLinks: List<MealLink>) : RecyclerView.Adapter<RecyclerViewAdapter.MealLinkLoadedViewHolder>() {

        override fun onCreateViewHolder(viewGroup: ViewGroup, viewType: Int): MealLinkLoadedViewHolder =
            MealLinkLoadedViewHolder(LayoutInflater.from(viewGroup.context).inflate(R.layout.treatments_bolus_carbs_item, viewGroup, false))

        override fun onBindViewHolder(holder: MealLinkLoadedViewHolder, position: Int) {
            val profile = profileFunction.getProfile() ?: return
            val ml = mealLinks[position]

            val newDay = position == 0 || !dateUtil.isSameDayGroup(timestamp(ml), timestamp(mealLinks[position - 1]))
            holder.binding.date.visibility = newDay.toVisibility()
            holder.binding.date.text = if (newDay) dateUtil.dateStringRelative(timestamp(ml), rh) else ""

            // 统计起始点标记：在起始记录上显示徽标
            holder.binding.statsStartMarker.visibility =
                (statsStartTimestamp > 0L && timestamp(ml) == statsStartTimestamp).toVisibility()

            // Metadata
            holder.binding.metadataLayout.visibility = (ml.bolusCalculatorResult != null && (ml.bolusCalculatorResult.isValid || showInvalidated)).toVisibility()
            ml.bolusCalculatorResult?.let { bolusCalculatorResult ->
                holder.binding.calcTime.text = dateUtil.timeString(bolusCalculatorResult.timestamp)
                holder.binding.metadataNs.visibility = (bolusCalculatorResult.ids.nightscoutId != null).toVisibility()
                holder.binding.cbMetadataRemove.visibility = (bolusCalculatorResult.isValid && actionHelper.isRemoving).toVisibility()
                if (actionHelper.isRemoving) {
                    holder.binding.cbMetadataRemove.setOnCheckedChangeListener { _, value ->
                        actionHelper.updateSelection(position, ml, value)
                    }
                    holder.binding.root.setOnClickListener {
                        holder.binding.cbMetadataRemove.toggle()
                        actionHelper.updateSelection(position, ml, holder.binding.cbMetadataRemove.isChecked)
                    }
                    holder.binding.cbMetadataRemove.isChecked = actionHelper.isSelected(position)
                }
            }

            // Bolus
            holder.binding.bolusLayout.visibility = (ml.bolus != null && (ml.bolus.isValid || showInvalidated)).toVisibility()
            ml.bolus?.let { bolus ->
                holder.binding.bolusTime.text = dateUtil.timeString(bolus.timestamp)
                holder.binding.insulin.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolus.amount)
                holder.binding.bolusNs.visibility = (bolus.ids.nightscoutId != null).toVisibility()
                holder.binding.bolusPump.visibility = bolus.ids.isPumpHistory().toVisibility()
                holder.binding.bolusInvalid.visibility = bolus.isValid.not().toVisibility()
                val iob = bolus.iobCalc(activePlugin, System.currentTimeMillis(), profile.dia)
                if (iob.iobContrib > 0.01) {
                    holder.binding.iob.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.activeColor))
                    holder.binding.iob.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, iob.iobContrib)
                    holder.binding.iobLabel.visibility = View.VISIBLE
                    holder.binding.iob.visibility = View.VISIBLE
                } else {
                    holder.binding.iob.text = rh.gs(app.aaps.core.ui.R.string.format_insulin_units, 0.0)
                    holder.binding.iob.setTextColor(holder.binding.insulin.currentTextColor)
                    holder.binding.iobLabel.visibility = View.GONE
                    holder.binding.iob.visibility = View.GONE
                }
                if (bolus.timestamp > dateUtil.now())
                    holder.binding.date.setTextColor(rh.gac(context, app.aaps.core.ui.R.attr.scheduledColor)) else holder.binding.date.setTextColor(holder.binding.carbs.currentTextColor)
                holder.binding.mealOrCorrection.text =
                    when (ml.bolus.type) {
                        BS.Type.SMB     -> "SMB"
                        BS.Type.NORMAL  -> rh.gs(R.string.meal_bolus)
                        BS.Type.PRIMING -> rh.gs(R.string.prime)
                    }
                holder.binding.cbBolusRemove.visibility = (ml.bolus.isValid && actionHelper.isRemoving).toVisibility()
                if (actionHelper.isRemoving) {
                    holder.binding.cbBolusRemove.setOnCheckedChangeListener { _, value ->
                        actionHelper.updateSelection(position, ml, value)
                    }
                    holder.binding.root.setOnClickListener {
                        holder.binding.cbBolusRemove.toggle()
                        actionHelper.updateSelection(position, ml, holder.binding.cbBolusRemove.isChecked)
                    }
                    holder.binding.cbBolusRemove.isChecked = actionHelper.isSelected(position)
                }
            }
            // Carbs
            holder.binding.carbsLayout.visibility = (ml.carbs != null && (ml.carbs.isValid || showInvalidated)).toVisibility()
            ml.carbs?.let { carbs ->
                holder.binding.carbsTime.text = dateUtil.timeString(carbs.timestamp)
                holder.binding.carbs.text = rh.gs(app.aaps.core.objects.R.string.format_carbs, carbs.amount.toInt())
                holder.binding.carbsDuration.text = if (carbs.duration > 0) rh.gs(app.aaps.core.ui.R.string.format_mins, T.msecs(carbs.duration).mins().toInt()) else ""
                holder.binding.carbsNs.visibility = (carbs.ids.nightscoutId != null).toVisibility()
                holder.binding.carbsPump.visibility = carbs.ids.isPumpHistory().toVisibility()
                holder.binding.carbsInvalid.visibility = carbs.isValid.not().toVisibility()
                holder.binding.cbCarbsRemove.visibility = (ml.carbs.isValid && actionHelper.isRemoving).toVisibility()
                if (actionHelper.isRemoving) {
                    holder.binding.cbCarbsRemove.setOnCheckedChangeListener { _, value ->
                        actionHelper.updateSelection(position, ml, value)
                    }
                    holder.binding.root.setOnClickListener {
                        holder.binding.cbCarbsRemove.toggle()
                        actionHelper.updateSelection(position, ml, holder.binding.cbCarbsRemove.isChecked)
                    }
                    holder.binding.cbCarbsRemove.isChecked = actionHelper.isSelected(position)
                }
            }

            holder.binding.calculation.tag = ml

            val notes = ml.carbs?.notes ?: ml.bolus?.notes ?: ""
            holder.binding.notes.text = notes
            holder.binding.notes.visibility = if (notes != "") View.VISIBLE else View.GONE
        }

        override fun getItemCount() = mealLinks.size

        /**
         * 根据列表位置找到"统计基准时间戳"。
         * 一条记录可能同时有 bolus / carbs / 计算结果，任一时间戳匹配即可。
         */
        private fun timestampAt(position: Int): Long {
            position.takeIf { it in mealLinks.indices } ?: return 0L
            val ml = mealLinks[position]
            return ml.bolus?.timestamp ?: ml.carbs?.timestamp ?: ml.bolusCalculatorResult?.timestamp ?: 0L
        }

        /** 生成起始点的可读描述，例如 " · 2.00U" 或 " · 30g" */
        private fun descriptionAt(position: Int): String {
            position.takeIf { it in mealLinks.indices } ?: return ""
            val ml = mealLinks[position]
            ml.bolus?.let { return " · " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, it.amount) }
            ml.carbs?.let { return " · " + rh.gs(app.aaps.core.objects.R.string.format_carbs, it.amount.toInt()) }
            return ""
        }

        inner class MealLinkLoadedViewHolder internal constructor(view: View) : RecyclerView.ViewHolder(view) {

            val binding = TreatmentsBolusCarbsItemBinding.bind(view)

            init {
                binding.calculation.setOnClickListener {
                    val mealLinkLoaded = it.tag as MealLink? ?: return@setOnClickListener
                    mealLinkLoaded.bolusCalculatorResult?.let { bolusCalculatorResult ->
                        WizardInfoDialog().also { wizardDialog ->
                            wizardDialog.arguments = Bundle().also { bundle ->
                                bundle.putString("data", Gson().toJson(bolusCalculatorResult).toString())
                            }
                            wizardDialog.show(childFragmentManager, "WizardInfoDialog")
                        }
                    }
                }
                binding.calculation.paintFlags = binding.calculation.paintFlags or Paint.UNDERLINE_TEXT_FLAG

                // 长按整条记录 → 将其设为"胰岛素累计统计"的起始点
                binding.root.setOnLongClickListener {
                    val pos = bindingAdapterPosition
                    if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener false
                    val ts = timestampAt(pos)
                    if (ts <= 0L) return@setOnLongClickListener false
                    setStatsStart(ts, descriptionAt(pos))
                    true
                }
            }
        }
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        this.menu = menu
        inflater.inflate(R.menu.menu_treatments_carbs_bolus, menu)
        updateMenuVisibility()
        val hasItems = (binding.recyclerview.adapter?.itemCount ?: 0) > 0
        menu.findItem(R.id.nav_delete_future)?.isVisible = hasItems
    }

    private fun updateMenuVisibility() {
        menu?.findItem(R.id.nav_hide_invalidated)?.isVisible = showInvalidated
        menu?.findItem(R.id.nav_show_invalidated)?.isVisible = !showInvalidated
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean =
        when (item.itemId) {
            R.id.nav_remove_items     -> actionHelper.startRemove()

            R.id.nav_show_invalidated -> {
                showInvalidated = true
                updateMenuVisibility()
                ToastUtils.infoToast(context, R.string.show_invalidated_records)
                swapAdapter()
                true
            }

            R.id.nav_hide_invalidated -> {
                showInvalidated = false
                updateMenuVisibility()
                ToastUtils.infoToast(context, R.string.hide_invalidated_records)
                swapAdapter()
                true
            }

            R.id.nav_delete_future    -> {
                deleteFutureTreatments()
                true
            }

            else                      -> false
        }

    private fun deleteFutureTreatments() {
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.overview_treatment_label), rh.gs(app.aaps.core.ui.R.string.delete_future_treatments) + "?", Runnable {
                disposable += persistenceLayer
                    .getBolusesFromTime(dateUtil.now(), false)
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list ->
                        list.forEach { bolus ->
                            disposable += persistenceLayer.invalidateBolus(bolus.id, Action.DELETE_FUTURE_TREATMENTS, Sources.Treatments, null, listOf()).subscribe()
                        }
                    }
                disposable += persistenceLayer
                    .getCarbsFromTimeNotExpanded(dateUtil.now(), false)
                    .subscribe { list ->
                        list.forEach { carb ->
                            if (carb.duration == 0L)
                                disposable += persistenceLayer.invalidateCarbs(
                                    carb.id,
                                    action = Action.CARBS_REMOVED,
                                    source = Sources.Treatments,
                                    listValues = listOf(ValueWithUnit.Timestamp(carb.timestamp))
                                ).subscribe()
                            else
                                disposable += persistenceLayer.cutCarbs(carb.id, dateUtil.now()).subscribe()
                        }
                    }
                disposable += persistenceLayer
                    .getBolusCalculatorResultsFromTime(dateUtil.now(), false)
                    .observeOn(aapsSchedulers.main)
                    .subscribe { list ->
                        list.forEach { bolusCalc ->
                            disposable += persistenceLayer.invalidateBolusCalculatorResult(
                                bolusCalc.id,
                                action = Action.BOLUS_CALCULATOR_RESULT_REMOVED,
                                source = Sources.Treatments,
                                listValues = listOf(ValueWithUnit.Timestamp(bolusCalc.timestamp))
                            ).subscribe()
                        }
                    }
            })
        }
    }

    private fun getConfirmationText(selectedItems: SparseArray<MealLink>): String {
        if (selectedItems.size() == 1) {
            val mealLink = selectedItems.valueAt(0)
            val bolus = mealLink.bolus
            if (bolus != null)
                return rh.gs(app.aaps.core.ui.R.string.configbuilder_insulin) + ": " + rh.gs(app.aaps.core.ui.R.string.format_insulin_units, bolus.amount) + "\n" +
                    rh.gs(app.aaps.core.ui.R.string.date) + ": " + dateUtil.dateAndTimeString(bolus.timestamp)
            val carbs = mealLink.carbs
            if (carbs != null)
                return rh.gs(app.aaps.core.ui.R.string.carbs) + ": " + rh.gs(app.aaps.core.objects.R.string.format_carbs, carbs.amount.toInt()) + "\n" +
                    rh.gs(app.aaps.core.ui.R.string.date) + ": " + dateUtil.dateAndTimeString(carbs.timestamp)
        }
        return rh.gs(app.aaps.core.ui.R.string.confirm_remove_multiple_items, selectedItems.size())
    }

    private fun removeSelected(selectedItems: SparseArray<MealLink>) {
        activity?.let { activity ->
            OKDialog.showConfirmation(activity, rh.gs(app.aaps.core.ui.R.string.removerecord), getConfirmationText(selectedItems), Runnable {
                selectedItems.forEach { _, ml ->
                    ml.bolus?.let { bolus ->
                        disposable += persistenceLayer.invalidateBolus(
                            bolus.id, action = Action.BOLUS_REMOVED,
                            source = Sources.Treatments,
                            listValues = listOf(
                                ValueWithUnit.Timestamp(bolus.timestamp),
                                ValueWithUnit.Insulin(bolus.amount)
                            )
                        ).subscribe()
                    }
                    ml.carbs?.let { carb ->
                        disposable += persistenceLayer.invalidateCarbs(
                            carb.id,
                            action = Action.CARBS_REMOVED,
                            source = Sources.Treatments,
                            listValues = listOf(
                                ValueWithUnit.Timestamp(carb.timestamp),
                                ValueWithUnit.Gram(carb.amount.toInt())
                            )
                        ).subscribe()
                    }
                    ml.bolusCalculatorResult?.let { bolusCalculatorResult ->
                        disposable += persistenceLayer.invalidateBolusCalculatorResult(
                            bolusCalculatorResult.id,
                            action = Action.BOLUS_CALCULATOR_RESULT_REMOVED,
                            source = Sources.Treatments,
                            listValues = listOf(ValueWithUnit.Timestamp(bolusCalculatorResult.timestamp))
                        ).subscribe()
                    }
                }
                actionHelper.finish()
            })
        }
    }

}
