package com.hermesandroid.relay.ui.components

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.hermesandroid.relay.R
import com.hermesandroid.relay.ui.theme.appearanceRoundedCornerShape

/** Navigation route of Settings -> Always connect via Tailscale ([com.hermesandroid.relay.ui.screens.TailscaleSettingsScreen]). */
const val TAILSCALE_SETTINGS_ROUTE = "settings/tailscale"

private const val TAILSCALE_APP_PACKAGE = "com.tailscale.ipn"
private const val TAILSCALE_MARKET_URI = "market://details?id=com.tailscale.ipn"
private const val TAILSCALE_PLAY_WEB_URI = "https://play.google.com/store/apps/details?id=com.tailscale.ipn"

/** What the "Always connect via Tailscale" surfaces render for the active connection. */
sealed interface TailscaleAlwaysConnectUiState {
    data object Off : TailscaleAlwaysConnectUiState
    data class On(val status: TailscaleAlwaysConnectStatus) : TailscaleAlwaysConnectUiState
}

enum class TailscaleAlwaysConnectStatus {
    Healthy,
    TailscaleAppMissing,
    TailnetUnavailable,           // includes "not connected", "not signed in", and "app excluded"
    NoTailscaleRouteConfigured,
    HostNotOnTailnet,
    HostUnreachableOverTailnet,
    ConnectedViaNonTailnet,       // violation / transition; must be visible
}

/** One remediation button. Order in [remediationActions] = button order. */
internal enum class TailscaleAlwaysConnectAction {
    InstallTailscale,
    OpenTailscale,
    ManageRoutes,
    TurnOffAndRetry,
}

internal fun TailscaleAlwaysConnectStatus.remediationActions(): List<TailscaleAlwaysConnectAction> =
    when (this) {
        TailscaleAlwaysConnectStatus.Healthy -> emptyList()
        TailscaleAlwaysConnectStatus.TailscaleAppMissing -> listOf(
            TailscaleAlwaysConnectAction.InstallTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.TailnetUnavailable -> listOf(
            TailscaleAlwaysConnectAction.OpenTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.NoTailscaleRouteConfigured -> listOf(
            TailscaleAlwaysConnectAction.ManageRoutes,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.HostNotOnTailnet -> listOf(
            TailscaleAlwaysConnectAction.ManageRoutes,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.HostUnreachableOverTailnet -> listOf(
            TailscaleAlwaysConnectAction.OpenTailscale,
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
        TailscaleAlwaysConnectStatus.ConnectedViaNonTailnet -> listOf(
            TailscaleAlwaysConnectAction.TurnOffAndRetry,
        )
    }

/** Body text of the blocked banner; null when the status is [TailscaleAlwaysConnectStatus.Healthy]. */
@StringRes
internal fun TailscaleAlwaysConnectStatus.blockedBodyRes(): Int? =
    when (this) {
        TailscaleAlwaysConnectStatus.Healthy -> null
        TailscaleAlwaysConnectStatus.TailscaleAppMissing ->
            R.string.settings_tailscale_required_body_app_missing
        TailscaleAlwaysConnectStatus.TailnetUnavailable ->
            R.string.settings_tailscale_required_body_not_connected
        TailscaleAlwaysConnectStatus.NoTailscaleRouteConfigured ->
            R.string.settings_tailscale_required_body_no_route
        TailscaleAlwaysConnectStatus.HostNotOnTailnet ->
            R.string.settings_tailscale_required_body_host_not_on_tailnet
        TailscaleAlwaysConnectStatus.HostUnreachableOverTailnet ->
            R.string.settings_tailscale_required_body_host_unreachable
        TailscaleAlwaysConnectStatus.ConnectedViaNonTailnet ->
            R.string.settings_tailscale_required_body_non_tailscale_active
    }

/** Opens the Tailscale store listing: Play Store app first, then the Play web page. */
internal fun openTailscaleInstallPage(context: Context) {
    val market = Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_MARKET_URI))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(market)
    } catch (_: ActivityNotFoundException) {
        val web = Intent(Intent.ACTION_VIEW, Uri.parse(TAILSCALE_PLAY_WEB_URI))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(web)
        } catch (_: ActivityNotFoundException) {
            // No store and no browser: nothing else can be opened.
        }
    }
}

/** Opens the Tailscale app; falls back to its store listing when it cannot be launched. */
internal fun openTailscaleApp(context: Context) {
    val launch = context.packageManager.getLaunchIntentForPackage(TAILSCALE_APP_PACKAGE)
    if (launch == null) {
        openTailscaleInstallPage(context)
        return
    }
    try {
        context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: ActivityNotFoundException) {
        openTailscaleInstallPage(context)
    }
}

/**
 * The ONE switch for "Always connect via Tailscale" plus its status / blocked banner and
 * remediation buttons. Rendered only by the Settings -> Always connect via Tailscale subpage;
 * the Routes tab shows [TailscaleAlwaysConnectStatusRow] instead (no second switch).
 * The switch stays enabled in every state so the policy can be changed while disconnected.
 */
@Composable
fun TailscaleAlwaysConnectCard(
    uiState: TailscaleAlwaysConnectUiState,
    onSetEnabled: (Boolean) -> Unit,
    onOpenTailscale: () -> Unit,
    onInstallTailscale: () -> Unit,
    onManageRoutes: () -> Unit,
    onTurnOffAndRetry: () -> Unit,
) {
    val isOn = uiState is TailscaleAlwaysConnectUiState.On
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = appearanceRoundedCornerShape(12.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tailscale-always-connect-card"),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .toggleable(
                        value = isOn,
                        role = Role.Switch,
                        onValueChange = onSetEnabled,
                    )
                    .testTag("tailscale-always-connect-toggle"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = stringResource(R.string.settings_tailscale_always_connect_title),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = if (isOn) {
                            stringResource(R.string.settings_tailscale_always_connect_subtitle_on)
                        } else {
                            stringResource(R.string.settings_tailscale_always_connect_subtitle_off)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = isOn,
                    onCheckedChange = null,
                )
            }
            Text(
                text = stringResource(R.string.settings_tailscale_always_connect_why),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (uiState) {
                TailscaleAlwaysConnectUiState.Off -> Unit
                is TailscaleAlwaysConnectUiState.On -> TailscaleAlwaysConnectStatusBlock(
                    status = uiState.status,
                    onOpenTailscale = onOpenTailscale,
                    onInstallTailscale = onInstallTailscale,
                    onManageRoutes = onManageRoutes,
                    onTurnOffAndRetry = onTurnOffAndRetry,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TailscaleAlwaysConnectStatusBlock(
    status: TailscaleAlwaysConnectStatus,
    onOpenTailscale: () -> Unit,
    onInstallTailscale: () -> Unit,
    onManageRoutes: () -> Unit,
    onTurnOffAndRetry: () -> Unit,
) {
    val bodyRes = status.blockedBodyRes()
    if (bodyRes == null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = stringResource(R.string.settings_tailscale_status_connected),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
    } else {
        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            shape = appearanceRoundedCornerShape(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("tailscale-always-connect-blocked"),
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.size(18.dp),
                    )
                    Text(
                        text = stringResource(R.string.settings_tailscale_required_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                    )
                }
                Text(
                    text = stringResource(bodyRes),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    status.remediationActions().forEach { action ->
                        when (action) {
                            TailscaleAlwaysConnectAction.InstallTailscale -> TextButton(onClick = onInstallTailscale) {
                                Text(stringResource(R.string.settings_tailscale_action_install_tailscale))
                            }
                            TailscaleAlwaysConnectAction.OpenTailscale -> TextButton(onClick = onOpenTailscale) {
                                Text(stringResource(R.string.settings_tailscale_action_open_tailscale))
                            }
                            TailscaleAlwaysConnectAction.ManageRoutes -> TextButton(onClick = onManageRoutes) {
                                Text(stringResource(R.string.settings_tailscale_action_manage_routes))
                            }
                            TailscaleAlwaysConnectAction.TurnOffAndRetry -> TextButton(onClick = onTurnOffAndRetry) {
                                Text(stringResource(R.string.settings_tailscale_action_turn_off))
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Read-only status line for the Routes tab. Tapping it opens the Settings subpage that owns
 * the switch; it deliberately has no switch of its own.
 */
@Composable
fun TailscaleAlwaysConnectStatusRow(
    uiState: TailscaleAlwaysConnectUiState,
    onClick: () -> Unit,
) {
    val blocked = uiState is TailscaleAlwaysConnectUiState.On &&
        uiState.status != TailscaleAlwaysConnectStatus.Healthy
    val subtitle = when (uiState) {
        TailscaleAlwaysConnectUiState.Off ->
            stringResource(R.string.settings_tailscale_always_connect_subtitle_off)
        is TailscaleAlwaysConnectUiState.On ->
            if (uiState.status == TailscaleAlwaysConnectStatus.Healthy) {
                stringResource(R.string.settings_tailscale_status_connected)
            } else {
                stringResource(R.string.settings_tailscale_required_title)
            }
    }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.42f),
        shape = appearanceRoundedCornerShape(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("tailscale-always-connect-status-row"),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                imageVector = Icons.Filled.VpnKey,
                contentDescription = null,
                tint = if (blocked) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
                modifier = Modifier.size(20.dp),
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_tailscale_always_connect_title),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (blocked) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
