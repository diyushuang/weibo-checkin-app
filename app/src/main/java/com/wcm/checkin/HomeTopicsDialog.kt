package com.wcm.checkin

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp

data class TopicsDialogUi(
    val uid: String,
    val st: String?,
    val name: String,
    val topics: List<WeiboTopicApi.Topic>,
)

/** 账号的超话清单：已签置灰勾选，待签可勾选后一键补签。 */
@Composable
fun TopicsDialog(
    state: TopicsDialogUi,
    onDismiss: () -> Unit,
    onResign: (List<WeiboTopicApi.Topic>) -> Unit,
    onViewAll: () -> Unit,
) {
    val pending = state.topics.filter { !it.done }
    var picked by remember { mutableStateOf(emptySet<String>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.home_topic_title, state.name)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = stringResource(
                        R.string.home_topic_list_summary,
                        state.topics.size,
                        state.topics.count { it.done },
                        pending.size,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                )
                TextButton(onClick = onViewAll) {
                    Text(stringResource(R.string.topic_view_all))
                }
                if (state.topics.isEmpty()) {
                    Text(
                        text = stringResource(R.string.home_topic_none),
                        style = MaterialTheme.typography.bodyMedium,
                        color = colorResource(R.color.ink_3),
                    )
                } else {
                    state.topics.forEach { topic ->
                        val checked = topic.done || topic.key in picked
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable(enabled = !topic.done) {
                                    picked = if (topic.key in picked) {
                                        picked - topic.key
                                    } else {
                                        picked + topic.key
                                    }
                                }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                enabled = !topic.done,
                            )
                            Text(
                                text = stringResource(
                                    if (topic.done) R.string.home_topic_item_done
                                    else R.string.home_topic_item_pending,
                                    topic.name,
                                ),
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (topic.done) colorResource(R.color.ink_3)
                                else colorResource(R.color.ink),
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            if (pending.isEmpty()) {
                TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
            } else {
                TextButton(onClick = {
                    onResign(pending.filter { it.key in picked })
                }) {
                    Text(stringResource(R.string.home_topic_resign))
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}