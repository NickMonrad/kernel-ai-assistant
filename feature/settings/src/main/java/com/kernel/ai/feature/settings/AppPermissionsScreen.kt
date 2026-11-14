package com.kernel.ai.feature.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.kernel.ai.core.permissions.PermissionRoute
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.kernel.ai.core.permissions.recordPermissionResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPermissionsScreen(
    onBack: () -> Unit = {},
    viewModel: AppPermissionsViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    // Live Activity value; read at refresh time, never during composition.
    val rationaleFor = remember(context) {
        { permission: String ->
            context.findActivity()?.let { activity ->
                ActivityCompat.shouldShowRequestPermissionRationale(activity, permission)
            } ?: false
        }
    }

    // Remembers which row launched the request so its result is recorded against that permission.
    var pendingPermission by remember { mutableStateOf<String?>(null) }
    val requestPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        pendingPermission?.let { permission ->
            context.recordPermissionResult(
                permission,
                granted,
                shouldShowRationale = !granted && rationaleFor(permission),
            )
        }
        pendingPermission = null
        viewModel.refresh(rationaleFor)
    }

    // Auto-refresh when returning from system settings (e.g. after toggling permissions)
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refresh(rationaleFor)
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
                    TextButton(onClick = { viewModel.refresh(rationaleFor) }) {
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
                PermissionRow(
                    item = perm,
                    onAction = {
                        when (perm.route) {
                            null, PermissionRoute.Granted -> Unit
                            PermissionRoute.RequestInApp -> {
                                pendingPermission = perm.permission
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
    onAction: () -> Unit,
) {
    val tint = if (item.isGranted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error

    // The action button is the only interactive element, so TalkBack exposes one action per row.
    ListItem(
        modifier = Modifier.fillMaxWidth(),
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
                val actionLabel = when (item.route) {
                    null, PermissionRoute.Granted -> null
                    PermissionRoute.RequestInApp -> stringResource(R.string.permission_action_allow)
                    PermissionRoute.AppInfo -> stringResource(R.string.permission_action_open_app_settings)
                    PermissionRoute.SpecialAccess -> stringResource(R.string.permission_action_open_settings)
                }
                if (actionLabel != null) {
                    TextButton(onClick = onAction) {
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
