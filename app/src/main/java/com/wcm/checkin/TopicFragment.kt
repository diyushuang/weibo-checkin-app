package com.wcm.checkin

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
 * 超话 Tab 的壳：状态与编排都在 [TopicViewModel]，这里只负责生命周期与首页回调。
 */
@AndroidEntryPoint
class TopicFragment : Fragment() {

    private val viewModel: TopicViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = ComposeView(requireContext()).apply {
        setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        setContent {
            TopicTab(
                viewModel = viewModel,
                onGoHome = { (activity as? MainActivity)?.selectHomeTab() },
            )
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewModel.onShown()
    }

    override fun onHiddenChanged(hidden: Boolean) {
        super.onHiddenChanged(hidden)
        if (!hidden) viewModel.onShown()
    }

    /** 首页点账号行 / 进度环时调用：切到指定账号。 */
    fun selectAccount(uid: String) {
        viewModel.selectAccount(uid)
    }
}