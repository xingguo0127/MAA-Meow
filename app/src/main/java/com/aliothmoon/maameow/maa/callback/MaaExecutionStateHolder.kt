package com.aliothmoon.maameow.maa.callback

import com.aliothmoon.maameow.domain.state.MaaExecutionState

interface MaaExecutionStateHolder {
    fun reportRunState(state: MaaExecutionState)

    /**
     * 所有任务自然完成(AllTasksCompleted=3)。**区别于**用户/floai 接管触发的
     * TaskChainStopped(10004)——后者仍走 [reportRunState]。默认语义等价于置 IDLE;
     * 实现方可在此追加收尾动作(如无头会话自动回收后台游戏虚拟屏)。
     */
    fun onAllTasksCompleted() = reportRunState(MaaExecutionState.IDLE)
}
