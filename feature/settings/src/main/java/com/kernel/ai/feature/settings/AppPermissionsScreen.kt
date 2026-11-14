package com.kernel.ai.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import com.kernel.ai.core.permissions.PermissionRoute

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsScreen(
    onBack: () -> Unit = {},
    viewModel: AppPermissionsViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()

    val context = LocalContext.current
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) {
        viewModel.refresh()
    }

    // Auto-refresh when returning from system settings (e.g. after toggling permissions)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("App Permissions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                        )
                    }
                },
                actions = {
                    TextButton(onClick = { viewModel.refresh() }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = "Refresh",
                            modifier = Modifier.padding(end = 4.dp),
                        )
                        Text("Refresh")
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Review the permissions and Android access Jandal uses for specific features. Allow requests access here. Special access opens Android settings.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(4.dp))

            uiState.permissions.forEach { perm ->
                val shouldShowRationale = context.findActivity()?.let { activity ->
                    ActivityCompat.shouldShowRequestPermissionRationale(activity, perm.permission)
                } ?: false
                val route = viewModel.routeFor(perm, shouldShowRationale)
                PermissionRow(
                    item = perm,
                    route = route,
                    onFix = {
                        when (route) {
                            PermissionRoute.Granted -> Unit
                            PermissionRoute.RequestInApp -> {
                                viewModel.markRuntimePermissionRequested(perm.permission)
                                requestPermissionLauncher.launch(perm.permission)
                            }
                            PermissionRoute.AppInfo -> viewModel.openRuntimePermissionAppInfo(perm.permission)
                            PermissionRoute.SpecialAccess -> viewModel.openSpecialPermissionSettings(perm.permission)
                        }
                    },
                )
                HorizontalDivider()
            }

        }
    }
}

@Composable
private fun PermissionRow(
    item: AppPermissionItem,
    route: PermissionRoute,
    onFix: () -> Unit,
) {
    val tint = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    ListItem(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                enabled = !item.isGranted,
                onClick = onFix,
            ),
        headlineContent = {
            Text(
                text = item.label,
                style = MaterialTheme.typography.titleMedium,
            )
        },
        supportingContent = {
            Text(
                text = item.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val actionLabel = when (route) {
                    PermissionRoute.Granted -> null
                    PermissionRoute.RequestInApp -> "Allow"
                    PermissionRoute.AppInfo -> "Open app settings"
                    PermissionRoute.SpecialAccess -> "Opens Android settings"
                }
                if (actionLabel != null) {
                    TextButton(onClick = onFix) {
                        Text(actionLabel)
                    }
                }
                Icon(
                    imageVector = if (item.isGranted) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = if (item.isGranted) "${item.label} granted" else "${item.label} not granted",
                    tint = tint,
                )
            }
        },
        overlineContent = {
            if (item.isSpecial) {
                Text(
                    text = "Special access",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        },
    )
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}