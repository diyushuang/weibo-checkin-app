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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

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

/** 首页账号列表：行点击进超话清单，⋮ / 长按打开账号菜单。 */
@Composable
fun AccountList(
    rows: List<AccountRowUi>,
    onRowClick: (String) -> Unit,
    onViewTopics: (String) -> Unit,
    onRelogin: (String) -> Unit,
    onDelete: (String) -> Unit,
) {
    if (rows.isEmpty()) {
        Text(
            text = stringResource(R.string.home_account_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = colorResource(R.color.ink_3),
            modifier = Modifier.padding(vertical = 16.dp),
        )
        return
    }
    Column {
        rows.forEachIndexed { index, row ->
            key(row.uid) {
                AccountRow(row, onRowClick, onViewTopics, onRelogin, onDelete)
            }
            if (index < rows.lastIndex) RowDivider()
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { onRowClick(row.uid) },
                onLongClick = { menuOpen = true },
            )
            .padding(vertical = 12.dp),
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
                Text(
                    text = "⋮",
                    style = MaterialTheme.typography.titleLarge,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.semantics { contentDescription = moreDesc },
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
                        onDelete(row.uid)
                    },
                )
            }
        }
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
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
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
    Surface(shape = RoundedCornerShape(999.dp), color = bg) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall,
            color = fg,
        )
    }
}

@Composable
private fun RowDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(colorResource(R.color.line)),
    )
}
