package app.aaps.core.keys

import app.aaps.core.keys.interfaces.LongNonPreferenceKey

enum class LongNonKey(
    override val key: String,
    override val defaultValue: Long,
    override val exportable: Boolean = true
) : LongNonPreferenceKey {

    LocalProfileLastChange("local_profile_last_change", 0L),
    BtWatchdogLastBark("bt_watchdog_last", 0L),
    ActivePumpChangeTimestamp("active_pump_change_timestamp", 0L),
    LastCleanupRun("last_cleanup_run", 0L),

    /**
     * 治疗统计：所选"起始治疗记录"的时间戳（0 = 未设置）。
     * 用于累计从该记录到现在的胰岛素总量，需要跨进程重启保留。
     */
    TreatmentStatsStartTimestamp("treatment_stats_start_timestamp", 0L),
}

