package com.wcm.checkin

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint

/**
 * 首页 Tab 的壳：状态与编排都在 [HomeViewModel]；这里只负责生命周期与页面跳转。
 */
@AndroidEntryPoint
class HomeFragment : Fragment() {

    private val viewModel: HomeViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            HomeTab(
                viewModel = viewModel,
                onOpenLogin = {
                    startActivity(Intent(requireContext(), LoginWebViewActivity::class.java))
                },
                onOpenSettings = { (activity as? MainActivity)?.selectSettingsTab() },
                onOpenTopics = { uid -> (activity as? MainActivity)?.selectTopicTab(uid ?: "") },
                onSetBadge = { count -> (activity as? MainActivity)?.setTopicBadge(count) },
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onShown()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResume()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.onShown()
    }
}