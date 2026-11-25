package com.seojin.assetguardian.data

import com.google.gson.annotations.SerializedName

/** 우대조건 체크리스트 항목 (서버 tasks 테이블) */
data class TaskItem(
    val id: Long = 0,
    @SerializedName("holding_id") val holdingId: String? = null,
    val product: String? = null,
    val title: String? = null,
    val detail: String? = null,
    val cadence: String? = null,           // monthly / once / until_maturity
    @SerializedName("due_day") val dueDay: Int = 0,
    @SerializedName("next_due") val nextDue: Double = 0.0,
    @SerializedName("done_count") val doneCount: Int = 0,
    val source: String? = null             // ai / rule
)

data class TasksResponse(val tasks: List<TaskItem>? = null, val source: String? = null)

/** 목표 추적 상태 (서버 goal_status) */
data class GoalStatus(
    val active: Boolean = false,
    val message: String? = null,
    val target: Long = 0,
    @SerializedName("monthly_plan") val monthlyPlan: Long = 0,
    val term: Int = 0,
    @SerializedName("start_month") val startMonth: String? = null,
    @SerializedName("months_needed_plan") val monthsNeededPlan: Int = 0,
    @SerializedName("months_recorded") val monthsRecorded: Int = 0,
    @SerializedName("planned_total") val plannedTotal: Long = 0,
    @SerializedName("actual_total") val actualTotal: Long = 0,
    val gap: Long = 0,
    val carried: Long = 0,                 // 재계획 전까지 모은 돈
    @SerializedName("progress_pct") val progressPct: Double = 0.0,
    val status: String? = null,            // no_data / on_track / behind / ahead
    val pace: Long = 0,
    @SerializedName("months_left_now") val monthsLeftNow: Int? = null,
    @SerializedName("delay_months") val delayMonths: Int? = null,
    val options: List<GoalOption>? = null,
    val records: List<ProgressRecord>? = null,
    @SerializedName("next_record_month") val nextRecordMonth: String? = null
)

data class GoalOption(
    val kind: String? = null,              // keep / catch_up / replan
    val title: String? = null,
    @SerializedName("months_left") val monthsLeft: Int? = null,
    val monthly: Long = 0,
    val text: String? = null
)

data class ProgressRecord(val month: String? = null, val actual: Long = 0)

/** AI 상담 */
data class ChatMsg(
    val id: Long = 0,
    val role: String? = null,              // user / assistant
    val text: String? = null,
    val trace: List<String>? = null,
    val at: Double = 0.0
)

data class ChatHistory(val messages: List<ChatMsg>? = null)

data class ChatReply(
    val reply: String? = null,
    val trace: List<String>? = null,
    @SerializedName("tool_calls") val toolCalls: List<String>? = null,
    val mode: String? = null
)

/** 주간 리포트 */
data class WeeklyReport(
    val at: Double = 0.0,
    val goal: GoalStatus? = null,
    @SerializedName("new_products") val newProducts: List<RecItem>? = null,
    @SerializedName("new_count") val newCount: Int = 0,
    @SerializedName("first_run") val firstRun: Boolean = false,
    val tasks: List<WeeklyTask>? = null,
    val maturities: List<WeeklyMaturity>? = null,
    val profile: WeeklyProfile? = null,
    @SerializedName("summary_lines") val summaryLines: List<String>? = null,
    @SerializedName("ai_summary") val aiSummary: String? = null
)

data class WeeklyTask(val title: String? = null, val product: String? = null, @SerializedName("next_due") val nextDue: Double = 0.0)
data class WeeklyMaturity(val name: String? = null, val days: Int = 0, val date: String? = null)
data class WeeklyProfile(
    val label: String? = null,
    val confidence: String? = null,
    @SerializedName("changed_from") val changedFrom: String? = null
)
