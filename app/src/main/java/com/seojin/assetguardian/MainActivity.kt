package com.seojin.assetguardian

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.content.Intent
import androidx.compose.runtime.mutableStateOf
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import com.seojin.assetguardian.ui.AppRoot
import com.seojin.assetguardian.ui.theme.AssetGuardianTheme

class MainActivity : ComponentActivity() {

    private val openState = mutableStateOf<String?>(null)

    private val askNotification =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33) askNotification.launch(Manifest.permission.POST_NOTIFICATIONS)
        if (savedInstanceState == null) openState.value = intent?.getStringExtra("open")
        setContent {
            AssetGuardianTheme {
                AppRoot(startTab = openState.value, onStartHandled = {
                    openState.value = null
                    intent?.removeExtra("open")
                })
            }
        }
    }

    /** 앱이 켜져 있을 때 알림을 눌러도 해당 화면으로 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openState.value = intent.getStringExtra("open")
    }
}
