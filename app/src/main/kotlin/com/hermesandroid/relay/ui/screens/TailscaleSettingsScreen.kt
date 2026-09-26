package com.hermesandroid.relay.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.data.EndpointCandidate
import com.hermesandroid.relay.data.primaryRouteUrl
import com.hermesandroid.relay.ui.components.RouteEditorDialog
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectCard
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectStatus
import com.hermesandroid.relay.ui.components.TailscaleAlwaysConnectUiState
import com.hermesandroid.relay.ui.components.openTailscaleApp
import com.hermesandroid.relay.ui.components.openTailscaleInstallPage
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape
import com.hermesandroid.relay.viewmodel.ConnectionViewModel

/**
 * Settings -> Always connect via Tailscale. Home of the only switch for the per-connection
 * policy, its blocked banner and remediation buttons, and the "only this app" checklist.
 * Hermes Relay can verify only its own connection; which other apps use Tailscale is set
 * in the Tailscale app and is never claimed here.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TailscaleSettingsScreen(
    connectionViewModel: ConnectionViewModel,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val activeConnection by connectionViewModel.activeConnection.collectAsState()
    val uiState by connectionViewModel.tailscaleAlwaysConnectUiState.collectAsState()
    val activeEndpoint by connectionViewModel.activeEndpoint.collectAsState()
    val endpointsFlow = remember(connectionViewModel) { connectionViewModel.observeDeviceEndpoints() }
    val endpoints: List<EndpointCandidate> by endpointsFlow.collectAsState(initial = emptyList())
    val routeProbeStatus: ConnectionViewModel.RouteProbeStatus =
        connectionViewModel.routeProbeStatus.collectAsState().value
    val isProbing = routeProbeStatus is ConnectionViewModel.RouteProbeStatus.Probing
    var routeEditorOpen by remember { mutableStateOf(false) }
    var verifyRequested by remember { mutableStateOf(false) }

    val isOn = uiState is TailscaleAlwaysConnectUiState.On
    val isHealthy = (uiState as? TailscaleAlwaysConnectUiState.On)?.status ==
        TailscaleAlwaysConnectStatus.Healthy

    Scaffold(
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.settings_back),
                        )
                    }
                },
                title = { Text(stringResource(R.string.settings_tailscale_always_connect_title)) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_tailscale_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val connection = activeConnection
            if (connection == null) {
                Text(
                    text = stringResource(R.string.settings_tailscale_no_active_connection),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(
                    text = stringResource(R.string.settings_tailscale_applies_to, connection.label),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TailscaleAlwaysConnectCard(
                    uiState = uiState,
                    onSetEnabled = { enabled ->
                        connectionViewModel.setTailscaleAlwaysConnectEnabled(enabled)
                    },
                    onOpenTailscale = { openTailscaleApp(context) },
                    onInstallTailscale = { openTailscaleInstallPage(context) },
                    onManageRoutes = { routeEditorOpen = true },
                    onTurnOffAndRetry = {
                        connectionViewModel.setTailscaleAlwaysConnectEnabled(false)
                        connectionViewModel.probeNow()
                    },
                )
                if (routeEditorOpen) {
                    RouteEditorDialog(
                        original = null,
                        initialRole = "tailscale",
                        relayEnabled = connection.relayUrl.isNotBlank() ||
                            endpoints.any { it.relay != null },
                        onSave = { role, dashboardUrl, onResult ->
                            connectionViewModel.saveExtraRoute(
                                role = role,
                                dashboardUrl = dashboardUrl,
                                original = null,
                                onResult = onResult,
                            )
                        },
                        onDismiss = { routeEditorOpen = false },
                    )
                }
            }

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = appearanceRoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("tailscale-only-this-app"),
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tailscale_only_this_app_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_only_this_app_body),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_install),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_split),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_select),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_step_verify),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_other_apps_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(
                        onClick = {
                            verifyRequested = true
                            connectionViewModel.probeNow()
                        },
                        enabled = isOn && !isProbing,
                        modifier = Modifier.testTag("tailscale-verify-connection"),
                    ) {
                        Text(stringResource(R.string.settings_tailscale_action_verify))
                    }
                    if (!isOn) {
                        Text(
                            text = stringResource(R.string.settings_tailscale_verify_needs_on),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else if (verifyRequested) {
                        when {
                            isProbing -> Text(
                                text = stringResource(R.string.active_section_checking),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            isHealthy -> Text(
                                text = stringResource(
                                    R.string.settings_tailscale_verify_ok,
                                    activeEndpoint?.primaryRouteUrl().orEmpty(),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                            else -> Text(
                                text = stringResource(R.string.settings_tailscale_verify_failed),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }
}
