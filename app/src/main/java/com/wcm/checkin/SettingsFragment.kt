package com.wcm.checkin

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import com.google.android.material.materialswitch.MaterialSwitch

/** 设置页：面板同步配置、行为开关、关于。 */
class SettingsFragment : Fragment() {

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
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()

        val etUrl = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPanelUrl)
        val etToken = view.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.etPanelToken)
        etUrl.setText(AccountStore.panelUrl(ctx))
        etToken.setText(AccountStore.panelToken(ctx))

        view.findViewById<View>(R.id.btnSavePanel).setOnClickListener {
            val raw = etUrl.text?.toString()?.trim() ?: ""
            val token = etToken.text?.toString() ?: ""
            if (raw.isEmpty()) {
                // 留空 = 关闭面板同步
                AccountStore.savePanel(ctx, "", token)
                Toast.makeText(ctx, R.string.settings_saved, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val parsed = try {
                PanelUrl.parse(raw)
            } catch (e: Exception) {
                // 公网明文、非法 scheme 等在这里就被挡下，不会等到同步时才失败
                etUrl.error = e.message
                return@setOnClickListener
            }
            AccountStore.savePanel(ctx, parsed.base, token)
            Toast.makeText(ctx, R.string.settings_saved, Toast.LENGTH_SHORT).show()
            ensureLocalNetworkPermission(parsed)
        }

        val switchAuto = view.findViewById<MaterialSwitch>(R.id.switchAutoOnOpen)
        switchAuto.isChecked = AccountStore.autoOnOpen(ctx)
        switchAuto.setOnCheckedChangeListener { _, checked ->
            AccountStore.setAutoOnOpen(ctx, checked)
        }
        // 整行可点：点标题/说明也能切换，不必精确点到那个小滑块
        view.findViewById<View>(R.id.rowAutoOnOpen).setOnClickListener { switchAuto.toggle() }

        val switchGif = view.findViewById<MaterialSwitch>(R.id.switchCrawlGif)
        switchGif.isChecked = AccountStore.crawlGif(ctx)
        switchGif.setOnCheckedChangeListener { _, checked ->
            AccountStore.setCrawlGif(ctx, checked)
        }
        view.findViewById<View>(R.id.rowCrawlGif).setOnClickListener { switchGif.toggle() }

        val version = try {
            ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName
        } catch (e: Exception) {
            "?"
        }
        view.findViewById<TextView>(R.id.tvAbout).text =
            getString(R.string.settings_about_body, version)
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
