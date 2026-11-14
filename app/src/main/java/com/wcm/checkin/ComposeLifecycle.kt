package com.wcm.checkin

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

/** 每次页面 RESUMED（首次进入 / 切 Tab 回来）时执行一次。 */
@Composable
fun OnResume(block: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    val latest by rememberUpdatedState(block)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) latest()
        }
        owner.lifecycle.addObserver(observer)
        if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) latest()
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}