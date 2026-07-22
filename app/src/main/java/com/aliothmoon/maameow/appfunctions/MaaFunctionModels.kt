package com.aliothmoon.maameow.appfunctions

import androidx.appfunctions.AppFunctionSerializable

/** 任务配置（Profile）摘要 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class ProfileInfo(
    /** Profile 唯一 ID（UUID 串） */
    val id: String,
    /** Profile 名称 */
    val name: String,
    /** 是否当前活跃配置 */
    val active: Boolean,
)

/** 启动任务的结果 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class LaunchResult(
    /** 是否成功开始执行 */
    val ok: Boolean,
    /** 结果码：STARTED / BUSY / PROFILE_NOT_FOUND / EMPTY_CHAIN / BLOCKED */
    val code: String,
    /** 人类可读说明 */
    val message: String,
)

/** MAA 当前运行状态 */
@AppFunctionSerializable(isDescribedByKDoc = true)
data class MaaStatus(
    /** 引擎状态：IDLE / STARTING / RUNNING / STOPPING / ERROR */
    val state: String,
    /** 当前活跃 profile 名称，无则空串 */
    val activeProfileName: String,
    /** 本轮任务节点总数（未跑过为 0） */
    val totalTasks: Int,
    /** 已完成节点数 */
    val completedTasks: Int,
    /** 正在执行的节点，无则空串 */
    val currentTask: String,
)
