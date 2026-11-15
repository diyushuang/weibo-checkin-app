package com.wcm.checkin

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * 单账号的重登凭证对话框：保存/删除账号密码与 ALC 票据、查看重登状态、立即重登。
 *
 * 凭证由 `CredentialVault` 用 Keystore 加密落盘，本对话框只收集输入并转成事件，
 * 不碰加解密，也不把密码写进任何日志或状态快照。
 */
data class ReloginDialogUi(
    val uid: String,
    val statusText: String,
    val hasCredential: Boolean,
    /** 是否已保存过 ALC（路线 E：不碰密码也能自动恢复）。 */
    val hasAlc: Boolean = false,
)

/**
 * 自动重登凭证弹窗。
 *
 * 两条路线并存、互不替代：
 * - **账号密码**：能力最全，但等于把微博密码托管在本机（Keystore 加密）；
 * - **ALC**：登录中心下发的中长期票据，凭它重签一次即可换新 `SUB`，
 *   不涉及密码。适合"愿意多点一次浏览器、但不愿交出密码"的用户。
 *
 * 密码只在本次输入中存在，保存即加密落盘；ALC 同理。
 */
@Composable
fun ReloginDialog(
    state: ReloginDialogUi,
    onDismiss: () -> Unit,
    onSave: (String, String, String) -> Boolean,
    onDelete: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var alc by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.relogin_dialog_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 460.dp)
                    .verticalScroll(rememberScrollState()),
            ) {
                Text(
                    text = state.statusText,
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text(stringResource(R.string.relogin_username_hint)) },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.relogin_password_hint)) },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                )
                Text(
                    text = stringResource(R.string.relogin_dialog_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                    modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
                )
                Text(
                    text = stringResource(R.string.relogin_alc_title),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                OutlinedTextField(
                    value = alc,
                    onValueChange = { alc = it },
                    label = { Text(stringResource(R.string.relogin_alc_hint)) },
                    singleLine = true,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                Text(
                    text = stringResource(
                        if (state.hasAlc) R.string.relogin_alc_saved else R.string.relogin_alc_desc,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = colorResource(R.color.ink_3),
                )
                TextButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    enabled = state.hasCredential || state.hasAlc,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.relogin_action_delete))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onSave(user.trim(), password, alc)) onDismiss()
            }) {
                Text(stringResource(R.string.relogin_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}