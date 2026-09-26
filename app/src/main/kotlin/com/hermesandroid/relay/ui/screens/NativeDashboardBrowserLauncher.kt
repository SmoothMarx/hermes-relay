package com.hermesandroid.relay.ui.screens

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import com.hermesandroid.relay.R
import com.hermesandroid.relay.diagnostics.DiagnosticCategory
import com.hermesandroid.relay.diagnostics.DiagnosticSeverity
import com.hermesandroid.relay.diagnostics.DiagnosticsLog
import com.hermesandroid.relay.network.shared.TailnetEnforcer

internal fun launchNativeDashboardAuthorization(
    context: Context,
    authorizationUrl: String,
) {
    TailnetEnforcer.get().checkUrl(authorizationUrl)?.let { reason ->
        // URL-gated entry point; UI surface (slice C) renders the blocked card.
        // Record the refusal so a silent no-launch is observable in Diagnostics,
        // mirroring ConnectionManager's blocked-dial entry.
        DiagnosticsLog.record(
            category = DiagnosticCategory.Relay,
            severity = DiagnosticSeverity.Error,
            title = context.getString(R.string.tailnet_diag_blocked),
            detail = reason.name,
            operation = "Open Dashboard authorization",
            configuredUrl = authorizationUrl,
            requestUrl = authorizationUrl,
        )
        return
    }
    val uri = Uri.parse(authorizationUrl)
    val customTab = CustomTabsIntent.Builder()
        .setShowTitle(true)
        .setShareState(CustomTabsIntent.SHARE_STATE_OFF)
        .build()
        .also {
            if (context !is Activity) {
                it.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    try {
        customTab.launchUrl(context, uri)
    } catch (_: ActivityNotFoundException) {
        context.startActivity(
            Intent(Intent.ACTION_VIEW, uri).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
