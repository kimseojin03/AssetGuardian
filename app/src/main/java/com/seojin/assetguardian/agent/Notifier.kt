package com.seojin.assetguardian.agent

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.seojin.assetguardian.MainActivity
import com.seojin.assetguardian.R
import com.seojin.assetguardian.data.Decision
import com.seojin.assetguardian.data.Verdict

object Notifier {
    const val CHANNEL_ID = "guardian_alerts"

    fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(CHANNEL_ID, "갈아타기 알림", NotificationManager.IMPORTANCE_DEFAULT)
            ch.description = "적금을 갈아타는 게 이득일 때 알려드려요"
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    /** 목표 루트 갱신 같은 일반 알림 (누르면 홈으로) */
    fun notifyText(context: Context, title: String, text: String, open: String? = null) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            if (open != null) putExtra("open", open)
        }
        val pi = PendingIntent.getActivity(context, (title + text).hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify((title + text).hashCode(), n)
        } catch (e: SecurityException) { /* 권한 거부 */ }
    }

    fun notifyDecision(context: Context, d: Decision) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        val title = when (d.verdict) {
            Verdict.SWITCH -> "지금 갈아타면 +%,d원 이득".format(d.netSure)
            Verdict.CONDITIONAL -> "우대조건 채우면 +%,d원 이득".format(d.netMax)
            Verdict.MATURITY -> "${d.myName} 만기가 다가와요"
            else -> return
        }
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("open", "inbox")
        }
        val pi = PendingIntent.getActivity(
            context, d.id.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(d.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(d.message))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(d.id.hashCode(), n)
        } catch (e: SecurityException) { /* 권한 거부 */ }
    }
}
