package com.seojin.assetguardian

import android.app.Application
import com.seojin.assetguardian.agent.AgentWorker
import com.seojin.assetguardian.agent.Notifier

class GuardianApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Notifier.createChannel(this)
        AgentWorker.schedule(this)   // 매일 오전 8시 에이전트 자동 실행 예약
    }
}
