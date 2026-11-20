package com.wcm.checkin

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * 首页账号列表卡：三行式账号行（用户名 / UID / 状态）、头像、点数 chip、⋮ 菜单与空态。
 *
 * 状态第一行回答「**今天**签了吗」，优先用今日超话快照；快照不可用时才退回本机上一次小结
 * 并标注「上次」。这个口径的判定在 `AccountStatusHead` 里，本文件只负责显示。
 */
enum class AvatarTone { OK, WARN, BRAND }

data class AccountChipsUi(val level: String?, val done: String?, val pending: String?)

data class AccountRowUi(
    val uid: String,
    val name: String,
    val uidText: String,
    val avatarInitial: String,
    val avatarTone: AvatarTone,
    val statusText: String,
    val chips: AccountChipsUi?,
)

/**
 * 首页账号列表：行点击进超话清单，⋮ / 长按打开账号菜单。
 *
 * 空态走 [WcmEmptyState]（结论 + 说明 + 动作）。v3.0 之前这里只有一行灰字，
 * 用户在「为什么没有账号」和「怎么加」之间没有任何引导 —— 而添加账号恰好是
 * 首页唯一必须完成的动作。
 */
@Composable
fun AccountList(
    rows: List<AccountRowUi>,
    onRowClick: (String) -> Unit,
    onViewTopics: (String) -> Unit,
    onRelogin: (String) -> Unit,
    onDelete: (String) -> Unit,
    onAdd: () -> Unit,
) {
    if (rows.isEmpty()) {
        WcmEmptyState(
            icon = R.drawable.ic_add,
            title = stringResource(R.string.home_account_empty_title),
            message = stringResource(R.string.home_account_empty),
            actionText = stringResource(R.string.home_account_empty_action),
            onAction = onAdd,
        )
        return
    }
    Column {
        rows.forEachIndexed { index, row ->
            key(row.uid) {
                AccountRow(row, onRowClick, onViewTopics, onRelogin, onDelete)
            }
            if (index < rows.lastIndex) WcmDivider()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AccountRow(
    row: AccountRowUi,
    onRowClick: (String) -> Unit,
    onViewTopics: (String) -> Unit,
    onRelogin: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onRowClick(row.uid) },
                onLongClick = { menuOpen = true },
            )
            .padding(vertical = WcmSpace.rowV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.avatarInitial, row.avatarTone)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = row.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                row.chips?.let {
                    Spacer(Modifier.width(8.dp))
                    AccountChips(it)
                }
            }
            Text(
                text = row.uidText,
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(R.color.ink_3),
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = row.statusText,
                style = MaterialTheme.typography.labelSmall,
                color = colorResource(R.color.ink_3),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
        Box {
            val moreDesc = stringResource(R.string.account_more)
            IconButton(onClick = { menuOpen = true }) {
                // v3.0：文字「⋮」换成矢量图 —— 字符字形由系统字体决定，大字号下会错位
                Icon(
                    painter = painterResource(R.drawable.ic_more_vert),
                    contentDescription = moreDesc,
                    tint = colorResource(R.color.ink_3),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_action_topics)) },
                    onClick = {
                        menuOpen = false
                        onViewTopics(row.uid)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_action_relogin)) },
                    onClick = {
                        menuOpen = false
                        onRelogin(row.uid)
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.account_action_delete)) },
                    onClick = {
                        menuOpen = false
                        confirmDelete = true
                    },
                )
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.home_delete_title, row.name)) },
            text = { Text(stringResource(R.string.home_delete_msg)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDelete = false
                        onDelete(row.uid)
                    },
                ) {
                    Text(stringResource(android.R.string.ok))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(stringResource(android.R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun Avatar(initial: String, tone: AvatarTone) {
    val (bg, fg) = when (tone) {
        AvatarTone.OK -> colorResource(R.color.ok_soft) to colorResource(R.color.ok_text)
        AvatarTone.WARN -> colorResource(R.color.warn_soft) to colorResource(R.color.warn_text)
        AvatarTone.BRAND -> colorResource(R.color.brand_soft) to colorResource(R.color.brand_strong)
    }
    Box(
        modifier = Modifier
            .size(38.dp)
            // 圆角归到 WcmShape.inner（10dp），与卡内其他嵌块同族
            .clip(WcmShape.inner)
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Medium,
            color = fg,
        )
    }
}

@Composable
private fun AccountChips(chips: AccountChipsUi) {
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        chips.level?.let {
            SmallPill(it, colorResource(R.color.surface_3), colorResource(R.color.ink_2))
        }
        chips.done?.let {
            SmallPill(it, colorResource(R.color.ok_soft), colorResource(R.color.ok_text))
        }
        chips.pending?.let {
            SmallPill(it, colorResource(R.color.warn_soft), colorResource(R.color.warn_text))
        }
    }
}

@Composable
private fun SmallPill(text: String, bg: Color, fg: Color) {
    Surface(shape = WcmShape.pill, color = bg) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}
