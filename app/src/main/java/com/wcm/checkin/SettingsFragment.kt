package com.wcm.checkin

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

/** 设置页：面板同步配置、行为开关、关于。 */
@AndroidEntryPoint
class SettingsFragment : Fragment() {

    private val viewModel: SettingsViewModel by viewModels()

    /**
     * Android 17（API 37）起，访问局域网默认被系统阻断，需要这个运行时权限
     * （除非改用系统设备选择器）。自建面板走的就是局域网，所以在保存配置时顺手申请。
     */
    private val lanPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val ctx = context ?: return@registerForActivityResult
            Toast.makeText(
                ctx,
                if (granted) R.string.settings_lan_granted else R.string.settings_lan_denied,
                Toast.LENGTH_LONG,
            ).show()
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            val state by viewModel.state.collectAsState()
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
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.events.collect { event ->
                    when (event) {
                        is SettingsEvent.Saved -> {
                            Toast.makeText(
                                requireContext(),
                                R.string.settings_saved,
                                Toast.LENGTH_SHORT,
                            ).show()
                            event.parsed?.let { ensureLocalNetworkPermission(it) }
                        }
                    }
                }
            }
        }

        // 已经配过面板就在进页面时申请一次：以前只在点「保存」时才申请，
        // 用户升级到 Android 17 后不重新保存就永远拿不到局域网权限，同步一直失败。
        viewModel.state.value.panelUrl
            .takeIf { it.isNotEmpty() }
            ?.let { runCatching { PanelUrl.parse(it) }.getOrNull() }
            ?.let { ensureLocalNetworkPermission(it) }
    }

    /**
     * 面板在局域网时，Android 17+ 需要 `ACCESS_LOCAL_NETWORK` 才能连上。
     * 低版本没有这个权限（也没有这个概念），直接跳过。
     */
    private fun ensureLocalNetworkPermission(parsed: PanelUrl.Parsed) {
        if (!parsed.isPrivateHost) return
        if (Build.VERSION.SDK_INT < 37) return
        val granted = ContextCompat.checkSelfPermission(
            requireContext(), PERM_ACCESS_LOCAL_NETWORK
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) lanPermission.launch(PERM_ACCESS_LOCAL_NETWORK)
    }

    private companion object {
        /** API 37 才存在的权限，不能引用 Build.VERSION_CODES 常量（compileSdk 36）。 */
        const val PERM_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }
}
