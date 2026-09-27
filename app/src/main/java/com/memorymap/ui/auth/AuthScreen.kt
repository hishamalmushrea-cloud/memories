package com.memorymap.ui.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Email
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.memorymap.R

/**
 * Sign up, sign in and password reset in one screen.
 *
 * When no Supabase project is configured the form is replaced by an explanation
 * and a single action, because there is no server to authenticate against: the
 * app then runs on a local account and everything stays on the device.
 */
@Composable
fun AuthScreen(viewModel: AuthViewModel = hiltViewModel()) {
    val ui by viewModel.ui.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text(
            text = stringResource(R.string.app_name),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = stringResource(R.string.auth_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (!viewModel.isCloudConfigured) {
            OfflineCard(onContinue = viewModel::continueOffline, busy = ui.isSubmitting)
            return@Column
        }

        TabRow(selectedTabIndex = ui.mode.ordinal.coerceAtMost(1)) {
            Tab(
                selected = ui.mode == AuthMode.SIGN_IN,
                onClick = { viewModel.onModeChange(AuthMode.SIGN_IN) },
                text = { Text(stringResource(R.string.auth_tab_sign_in)) },
            )
            Tab(
                selected = ui.mode == AuthMode.SIGN_UP,
                onClick = { viewModel.onModeChange(AuthMode.SIGN_UP) },
                text = { Text(stringResource(R.string.auth_tab_sign_up)) },
            )
        }

        Card(Modifier.fillMaxWidth()) {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (ui.mode == AuthMode.SIGN_UP) {
                    OutlinedTextField(
                        value = ui.displayName,
                        onValueChange = viewModel::onDisplayNameChange,
                        label = { Text(stringResource(R.string.auth_display_name)) },
                        leadingIcon = { Icon(Icons.Outlined.Person, contentDescription = null) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = ui.email,
                    onValueChange = viewModel::onEmailChange,
                    label = { Text(stringResource(R.string.auth_email)) },
                    leadingIcon = { Icon(Icons.Outlined.Email, contentDescription = null) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (ui.mode != AuthMode.RESET_PASSWORD) {
                    OutlinedTextField(
                        value = ui.password,
                        onValueChange = viewModel::onPasswordChange,
                        label = { Text(stringResource(R.string.auth_password)) },
                        leadingIcon = { Icon(Icons.Outlined.Lock, contentDescription = null) },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(
                            keyboardType = KeyboardType.Password,
                            imeAction = ImeAction.Done,
                        ),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                ui.messageKey?.let { key ->
                    Text(
                        text = authMessage(key),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (key == "auth_reset_sent") {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                }

                Button(
                    onClick = viewModel::submit,
                    enabled = !ui.isSubmitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (ui.isSubmitting) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp).width(18.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        stringResource(
                            when (ui.mode) {
                                AuthMode.SIGN_IN -> R.string.auth_action_sign_in
                                AuthMode.SIGN_UP -> R.string.auth_action_sign_up
                                AuthMode.RESET_PASSWORD -> R.string.auth_action_reset
                            },
                        ),
                    )
                }

                if (ui.mode == AuthMode.SIGN_IN) {
                    TextButton(
                        onClick = { viewModel.onModeChange(AuthMode.RESET_PASSWORD) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_forgot_password))
                    }
                } else if (ui.mode == AuthMode.RESET_PASSWORD) {
                    TextButton(
                        onClick = { viewModel.onModeChange(AuthMode.SIGN_IN) },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(R.string.auth_back_to_sign_in))
                    }
                }
            }
        }

        OutlinedButton(onClick = viewModel::continueOffline, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.auth_continue_offline))
        }
    }
}

@Composable
private fun OfflineCard(onContinue: () -> Unit, busy: Boolean) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(12.dp))
                Text(stringResource(R.string.auth_offline_title), style = MaterialTheme.typography.titleMedium)
            }
            Text(
                text = stringResource(R.string.auth_offline_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = onContinue, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.auth_start_local))
            }
        }
    }
}

/** Resolves the repository's stable message keys to localized text. */
@Composable
private fun authMessage(key: String): String = stringResource(
    when (key) {
        "auth_error_credentials" -> R.string.auth_error_credentials
        "auth_error_weak_password" -> R.string.auth_error_weak_password
        "auth_error_email_taken" -> R.string.auth_error_email_taken
        "auth_error_email_unconfirmed" -> R.string.auth_error_email_unconfirmed
        "auth_error_rate_limited" -> R.string.auth_error_rate_limited
        "auth_error_offline" -> R.string.auth_error_offline
        "auth_error_email_required" -> R.string.auth_error_email_required
        "auth_reset_sent" -> R.string.auth_reset_sent
        else -> R.string.auth_error_generic
    },
)
