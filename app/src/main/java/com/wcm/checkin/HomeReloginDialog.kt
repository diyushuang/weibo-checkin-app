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

data class ReloginDialogUi(
    val uid: String,
    val statusText: String,
    val hasCredential: Boolean,
)

/** 自动重登凭证弹窗：密码只在本次输入中存在，保存即加密落盘。 */
@Composable
fun ReloginDialog(
    state: ReloginDialogUi,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Boolean,
    onDelete: () -> Unit,
) {
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
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
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    onClick = {
                        onDelete()
                        onDismiss()
                    },
                    enabled = state.hasCredential,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(stringResource(R.string.relogin_action_delete))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (onSave(user.trim(), password)) onDismiss()
            }) {
                Text(stringResource(R.string.relogin_action_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}