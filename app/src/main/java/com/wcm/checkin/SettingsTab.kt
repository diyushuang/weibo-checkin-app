package com.wcm.checkin

import android.content.pm.PackageManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext

/** 设置 Tab：Compose 页面 + 局域网权限申请/保存事件。 */
@Composable
fun SettingsTab(viewModel: SettingsViewModel) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    val lanPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        Toast.makeText(
            context,
            if (granted) R.string.settings_lan_granted else R.string.settings_lan_denied,
            Toast.LENGTH_LONG,
        ).show()
    }

    fun requestLanPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 37) return
        val granted = ContextCompat.checkSelfPermission(
            context, PERM_ACCESS_LOCAL_NETWORK,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) lanPermission.launch(PERM_ACCESS_LOCAL_NETWORK)
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is SettingsEvent.Saved -> {
                    Toast.makeText(context, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                    if (event.needsLanPermission) requestLanPermissionIfNeeded()
                }
            }
        }
    }
    LaunchedEffect(Unit) {
        // 已经配过面板就在进页面时申请一次：升级到 Android 17 后不重新保存也能拿到权限
        if (viewModel.currentNeedsLanPermission()) requestLanPermissionIfNeeded()
    }

    WcmTheme {
        SettingsScreen(
            state = state,
            onUrlChange = viewModel::updateUrl,
            onTokenChange = viewModel::updateToken,
            onSave = viewModel::save,
            onAutoOnOpenChange = viewModel::setAutoOnOpen,
            onCrawlGifChange = viewModel::setCrawlGif,
            onAutoReloginChange = viewModel::setAutoRelogin,
        )
    }
}

/** API 37 才存在的权限，不能引用 Build.VERSION_CODES 常量（compileSdk 36）。 */
private const val PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"