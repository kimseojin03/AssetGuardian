package com.seojin.assetguardian.agent

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * 매일 아침 자동으로 에이전트를 깨우는 백그라운드 작업.
 * 사용자가 앱을 열지 않아도 안드로이드가 하루 한 번 실행해 준다.
 */
class AgentWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = AgentRunner.runOnce(applicationContext)
        return if (outcome.error != null) Result.retry() else Result.success()
    }

    companion object {
        private const val NAME = "daily_guardian"

        fun schedule(context: Context) {
            // 다음 오전 8시까지 남은 시간만큼 첫 실행을 미룸
            val now = LocalDateTime.now()
            var next = now.toLocalDate().atTime(LocalTime.of(8, 0))
            if (!next.isAfter(now)) next = next.plusDays(1)
            val delay = Duration.between(now, next)

            val req = PeriodicWorkRequestBuilder<AgentWorker>(Duration.ofDays(1))
                .setInitialDelay(delay)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, req)
        }
    }
}
