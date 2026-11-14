package com.wcm.checkin

import android.app.DatePickerDialog
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/** 图库抓取 Tab：只渲染 [GalleryViewModel]，目录选择器由 Fragment 壳注入。 */
@Composable
fun GalleryTab(
    viewModel: GalleryViewModel,
    onPickDir: () -> Unit,
    onBrowse: (String) -> Unit,
    onOpenGallery: (String) -> Unit,
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    var addOpen by remember { mutableStateOf(false) }
    var addInput by remember { mutableStateOf("") }
    var singleOpen by remember { mutableStateOf(false) }
    var singleInput by remember { mutableStateOf("") }
    var fullUids by remember { mutableStateOf<Set<String>?>(null) }
    var deleteUid by remember { mutableStateOf<String?>(null) }
    var sinceUid by remember { mutableStateOf<String?>(null) }
    var retryItems by remember { mutableStateOf<List<String>?>(null) }
    var postPicker by remember { mutableStateOf<List<WeiboPostApi.RawPost>?>(null) }

    OnResume { viewModel.onShown() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is GalleryEvent.Toast -> Toast.makeText(context, event.text, Toast.LENGTH_SHORT).show()
                is GalleryEvent.ShowRetry -> retryItems = event.items
                is GalleryEvent.ShowPostPicker -> postPicker = event.posts
            }
        }
    }

    WcmTheme {
        GalleryScreen(
            state = state,
            onAddTarget = {
                addInput = ""
                addOpen = true
            },
            onPickDir = onPickDir,
            onOpenGallery = {
                val raw = CrawlSettings.crawlTreeUri(context)
                if (raw.isEmpty()) {
                    Toast.makeText(context, R.string.gallery_need_dir, Toast.LENGTH_SHORT).show()
                } else {
                    onOpenGallery(raw)
                }
            },
            onModeToggle = viewModel::setMode,
            onAutoToggle = viewModel::setAuto,
            onStart = viewModel::startAll,
            onFull = { fullUids = emptySet() },
            onStop = viewModel::stop,
            onRetryFailed = { viewModel.loadRetryItems() },
            onBrowse = {
                val first = CrawlSettings.crawlTargets(context).firstOrNull()?.uid ?: ""
                onBrowse(first)
            },
            onSinglePost = {
                singleInput = ""
                singleOpen = true
            },
            onLogToggle = { viewModel.setLogExpanded(!state.log.expanded) },
            onTargetIncremental = { uid -> viewModel.startCrawl(setOf(uid), full = false) },
            onTargetFull = { uid -> fullUids = setOf(uid) },
            onTargetSince = { uid -> sinceUid = uid },
            onTargetDelete = { uid -> deleteUid = uid },
        )
    }

    if (addOpen) {
        AlertDialog(
            onDismissRequest = { addOpen = false },
            title = { Text(stringResource(R.string.crawl_add_title)) },
            text = {
                OutlinedTextField(
                    value = addInput,
                    onValueChange = { addInput = it },
                    singleLine = true,
                    label = { Text(stringResource(R.string.crawl_add_hint)) },
                    supportingText = { Text(stringResource(R.string.crawl_add_example)) },
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        addOpen = false
                        viewModel.addTarget(addInput)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { addOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    if (singleOpen) {
        AlertDialog(
            onDismissRequest = { singleOpen = false },
            title = { Text(stringResource(R.string.single_title)) },
            text = {
                Column {
                    OutlinedTextField(
                        value = singleInput,
                        onValueChange = { singleInput = it },
                        singleLine = true,
                        label = { Text(stringResource(R.string.single_input_hint)) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = stringResource(R.string.single_input_note),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        singleOpen = false
                        viewModel.startSingleInput(singleInput)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { singleOpen = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    fullUids?.let { uids ->
        AlertDialog(
            onDismissRequest = { fullUids = null },
            title = {
                Text(
                    stringResource(
                        R.string.crawl_full_confirm_title,
                        viewModel.confirmFullName(uids),
                    ),
                )
            },
            text = { Text(stringResource(R.string.crawl_full_confirm_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        fullUids = null
                        viewModel.startCrawl(uids, full = true)
                    },
                ) { Text(stringResource(R.string.crawl_full_confirm_ok)) }
            },
            dismissButton = {
                TextButton(onClick = { fullUids = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    deleteUid?.let { uid ->
        val target = viewModel.targetOf(uid)
        AlertDialog(
            onDismissRequest = { deleteUid = null },
            title = {
                Text(stringResource(R.string.crawl_remove_title, target?.name ?: uid))
            },
            text = { Text(stringResource(R.string.crawl_remove_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteUid = null
                        viewModel.removeTarget(uid)
                    },
                ) { Text(stringResource(android.R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteUid = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    retryItems?.let { items ->
        AlertDialog(
            onDismissRequest = { retryItems = null },
            title = { Text(stringResource(R.string.crawl_retry_title, items.size)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(items) { line ->
                        Text(
                            text = line,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        retryItems = null
                        viewModel.runRetryOnly()
                    },
                ) { Text(stringResource(R.string.crawl_retry_action)) }
            },
            dismissButton = {
                TextButton(onClick = { retryItems = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    postPicker?.let { posts ->
        AlertDialog(
            onDismissRequest = { postPicker = null },
            title = { Text(stringResource(R.string.single_pick_title, posts.size)) },
            text = {
                LazyColumn(modifier = Modifier.heightIn(max = 400.dp)) {
                    items(posts) { post ->
                        Text(
                            text = postPickerLine(post),
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    postPicker = null
                                    viewModel.startSingleBid(post.bid)
                                }
                                .padding(vertical = 8.dp),
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { postPicker = null }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }

    sinceUid?.let { uid ->
        SinceDatePicker(
            uid = uid,
            current = viewModel.currentSince(uid),
            onPicked = { picked ->
                sinceUid = null
                viewModel.setSince(uid, picked)
            },
            onDismiss = { sinceUid = null },
        )
    }
}

@Composable
private fun SinceDatePicker(
    uid: String,
    current: String,
    onPicked: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    DisposableEffect(uid, current) {
        val initial = runCatching { LocalDate.parse(current) }.getOrNull() ?: LocalDate.now()
        val dialog = DatePickerDialog(
            context,
            { _, year, month, day ->
                onPicked(String.format(Locale.US, "%04d-%02d-%02d", year, month + 1, day))
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth,
        ).apply {
            datePicker.maxDate = System.currentTimeMillis()
            setOnCancelListener { onDismiss() }
            show()
        }
        onDispose { dialog.dismiss() }
    }
}

private fun postPickerLine(post: WeiboPostApi.RawPost): String {
    val t = WeiboPostApi.parseWeiboTime(post.createdAt)
    val whenStr = if (t != null) {
        "${t.first.substring(5)} ${t.second.substring(0, 5)}"
    } else {
        "未知时间"
    }
    val tags = buildString {
        if (post.pinned) append(" · 置顶")
        if (post.isRetweet) append(" · 转发")
        else if (post.picNum > 0) append(" · ${post.picNum}图")
        else append(" · 无图")
    }
    return "$whenStr$tags\n${post.text.ifEmpty { "（无文字）" }}"
}