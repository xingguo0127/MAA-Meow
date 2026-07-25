package com.aliothmoon.maameow.appfunctions

import androidx.appfunctions.AppFunctionContext
import androidx.appfunctions.service.AppFunction
import com.aliothmoon.maameow.data.preferences.TaskChainState
import com.aliothmoon.maameow.domain.service.MaaCompositionService
import com.aliothmoon.maameow.domain.state.MaaExecutionState
import com.aliothmoon.maameow.domain.usecase.PrepareTaskStartUseCase
import com.aliothmoon.maameow.domain.usecase.TaskStartContext
import com.aliothmoon.maameow.domain.usecase.TaskStartDecision
import com.aliothmoon.maameow.domain.usecase.TaskStartMode
import com.aliothmoon.maameow.maa.callback.TaskChainStatusTracker
import com.aliothmoon.maameow.maa.callback.TaskRunStatus
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean

/** FlowOS 对话集成入口：暴露给系统 agent 的三个函数（fork 专属，diff 独立成包便于跟上游 rebase） */
class MaaFunctions(
    private val chainState: TaskChainState,
    private val prepareTaskStart: PrepareTaskStartUseCase,
    private val composition: MaaCompositionService,
    private val statusTracker: TaskChainStatusTracker,
) {
    // 对齐 ForegroundScheduleStarter.executing：RUNNING/STARTING 状态检查只能挡住已在跑的任务，
    // 挡不住两个并发调用同时通过检查（状态尚未扳到 STARTING 的窗口期）导致双启动/串 profile。
    private val executing = AtomicBoolean(false)

    /** 列出全部任务配置（Profile） */
    @AppFunction
    suspend fun listProfiles(context: AppFunctionContext): List<ProfileInfo> {
        chainState.isLoaded.first { it }
        val activeId = chainState.activeProfileId.value
        return chainState.profiles.value.map { ProfileInfo(it.id, it.name, it.id == activeId) }
    }

    /**
     * 启动指定任务配置。不等待 [ForegroundScheduleStarter][com.aliothmoon.maameow.schedule.service.ForegroundScheduleStarter]
     * 那样的 30 秒倒计时，通过前置检查后立即进入执行。
     * @param profileId 目标配置的 UUID（从 listProfiles 获取）
     */
    @AppFunction
    suspend fun launchProfile(context: AppFunctionContext, profileId: String): LaunchResult {
        if (!executing.compareAndSet(false, true)) {
            return LaunchResult(false, "BUSY", "另一个启动请求正在处理中")
        }
        try {
            return launchProfileLocked(profileId)
        } finally {
            executing.set(false)
        }
    }

    private suspend fun launchProfileLocked(profileId: String): LaunchResult {
        // 忙碌判定对齐 ForegroundScheduleStarter.executeSilentStart：只有 RUNNING/STARTING 算占用，
        // STOPPING/ERROR 不拦截（executeStart 内部会把状态直接扳到 STARTING）。
        val runState = composition.state.value
        if (runState == MaaExecutionState.RUNNING || runState == MaaExecutionState.STARTING) {
            return LaunchResult(false, "BUSY", "已有任务在执行（$runState）")
        }

        chainState.isLoaded.first { it }
        val profile = chainState.profiles.value.firstOrNull { it.id == profileId }
            ?: return LaunchResult(false, "PROFILE_NOT_FOUND", "找不到配置 $profileId")

        // 仅在目标不是当前活跃配置时才切换，避免每次调用都触发一次无意义的持久化写入
        if (chainState.activeProfileId.value != profileId) {
            chainState.switchProfile(profileId)
        }

        val chain = chainState.chain.value.filter { it.enabled }
        if (chain.isEmpty()) {
            return LaunchResult(false, "EMPTY_CHAIN", "配置「${profile.name}」没有启用的任务节点")
        }

        return try {
            when (val decision = prepareTaskStart(chain, TaskStartContext(TaskStartMode.SCHEDULED))) {
                is TaskStartDecision.Ready -> {
                    val result = composition.start(
                        tasks = decision.plan.params,
                        clientType = decision.plan.clientType,
                        isScheduled = true,
                    )
                    if (result is MaaCompositionService.StartResult.Success) {
                        // 后台模式带回虚拟屏 displayId，供 floai push live_display_card；前台模式无屏为 -1
                        val displayId = composition.activeVirtualDisplayId.value
                        // 无头(AppFunction)会话没有 UI owner 回收虚拟屏：武装自动回收，任务自然打完后
                        // 兜底释放后台游戏虚拟屏，避免明日方舟空跑烧 CPU（见 onAllTasksCompleted）。
                        if (displayId >= 0) composition.armAutoReclaimOnCompletion()
                        LaunchResult(
                            true, "STARTED", "已开始执行「${profile.name}」",
                            displayId = displayId
                        )
                    } else {
                        // MaaCore 启动失败（资源/连接/实例初始化等）。三码契约里没有专门的失败码，
                        // 归入 BLOCKED——语义上都是「请求没能真正跑起来」。
                        LaunchResult(false, "BLOCKED", "MAA 核心启动失败：$result")
                    }
                }

                is TaskStartDecision.Blocked ->
                    LaunchResult(false, "BLOCKED", "任务被前置检查拦截：${decision.reason}")

                // SCHEDULED 模式下闸门只产出 Ready/Blocked，RequiresConfirmation 仅 MANUAL 模式触发
                // （见 ForegroundScheduleStarter 同名注释）。AppFunction 入口没有交互式确认通道，兜底按 BLOCKED 处理。
                is TaskStartDecision.RequiresConfirmation ->
                    LaunchResult(false, "BLOCKED", "任务需要用户确认，AppFunction 入口不支持交互式确认")
            }
        } catch (e: Exception) {
            LaunchResult(false, "BLOCKED", "启动异常：${e.message}")
        }
    }

    /** 查询当前运行状态与进度 */
    @AppFunction
    suspend fun getStatus(context: AppFunctionContext): MaaStatus {
        chainState.isLoaded.first { it }
        val activeId = chainState.activeProfileId.value
        val activeName = chainState.profiles.value.firstOrNull { it.id == activeId }?.name ?: ""
        val tasks = statusTracker.tasks.value
        return MaaStatus(
            state = composition.state.value.name,
            activeProfileName = activeName,
            totalTasks = tasks.size,
            completedTasks = tasks.count { it.status == TaskRunStatus.COMPLETED },
            currentTask = tasks.firstOrNull { it.status == TaskRunStatus.IN_PROGRESS }?.taskChain ?: "",
        )
    }
}
