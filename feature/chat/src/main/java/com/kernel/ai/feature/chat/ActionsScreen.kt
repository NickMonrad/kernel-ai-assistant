package com.kernel.ai.feature.chat

import android.Manifest
import android.content.pm.PackageManager
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.kernel.ai.R
import com.kernel.ai.core.skills.QuickIntentRouter
import com.kernel.ai.core.voice.VoiceCaptureMode
import com.kernel.ai.feature.chat.ActionsViewModel.VoiceCaptureState
import com.kernel.ai.feature.chat.model.ToolCallInfo
import kotlinx.coroutines.launch

private const val ACTIONS_SCREEN_TAG = "ActionsScreen"

@Composable
fun ActionsScreen(
    viewModel: ActionsViewModel = hiltViewModel(),
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onQuickActionClick: (QuickIntentRouter.ActionInfo) -> Unit,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    ActionsContent(
        state = state,
        onBack = onBack,
        onNavigateToSettings = onNavigateSettings,
        onDeleteAction = { action ->
            scope.launch {
                viewModel.deleteAction(action)
            }
        },
        onEditAction = { action ->
            viewModel.editAction(action)
        },
        onSendAction = { action ->
            viewModel.sendAction(action)
        },
        onQuickActionClick = onQuickActionClick,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionsContent(
    state: ActionsViewModel.UiState,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onDeleteAction: (ActionsViewModel.ActionItem) -> Unit,
    onEditAction: (ActionsViewModel.ActionItem) -> Unit,
    onSendAction: (ActionsViewModel.ActionItem) -> Unit,
    onQuickActionClick: (QuickIntentRouter.ActionInfo) -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf(stringResource(R.string.quick_actions), stringResource(R.string.saved_actions))

    Scaffold(
        topBar = {
            TopAppBar(
                title = stringResource(R.string.quick_actions),
                onBack = onBack,
                onNavigateToSettings = onNavigateToSettings,
            )
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(
                selectedTabIndex = selectedTab,
                indicator = { tabPositions ->
                    TabRowDefaults.Indicator(
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    )
                },
            ) {
                tabs.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title) },
                    )
                }
            }

            when (selectedTab) {
                0 -> QuickActionsTab(
                    state = state,
                    onDeleteAction = onDeleteAction,
                    onEditAction = onEditAction,
                    onSendAction = onSendAction,
                    onQuickActionClick = onQuickActionClick,
                )
                1 -> SavedActionsTab(
                    state = state,
                    onDeleteAction = onDeleteAction,
                    onEditAction = onEditAction,
                    onSendAction = onSendAction,
                )
            }
        }
    }
}

@Composable
private fun QuickActionsTab(
    state: ActionsViewModel.UiState,
    onDeleteAction: (ActionsViewModel.ActionItem) -> Unit,
    onEditAction: (ActionsViewModel.ActionItem) -> Unit,
    onSendAction: (ActionsViewModel.ActionItem) -> Unit,
    onQuickActionClick: (QuickIntentRouter.ActionInfo) -> Unit,
) {
    when (state) {
        is ActionsViewModel.UiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is ActionsViewModel.UiState.Success -> {
            if (state.actions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.no_quick_actions))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.actions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            onDelete = { onDeleteAction(action) },
                            onEdit = { onEditAction(action) },
                            onSend = { onSendAction(action) },
                            onClick = { onQuickActionClick(action.info) },
                        )
                    }
                }
            }
        }
        is ActionsViewModel.UiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.error_loading_actions),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { /* TODO: retry */ }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun SavedActionsTab(
    state: ActionsViewModel.UiState,
    onDeleteAction: (ActionsViewModel.ActionItem) -> Unit,
    onEditAction: (ActionsViewModel.ActionItem) -> Unit,
    onSendAction: (ActionsViewModel.ActionItem) -> Unit,
) {
    // Reuse the same actions list for saved actions
    // In a real implementation, this would fetch from a different source
    when (state) {
        is ActionsViewModel.UiState.Loading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                CircularProgressIndicator()
            }
        }
        is ActionsViewModel.UiState.Success -> {
            if (state.actions.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(R.string.no_saved_actions))
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(state.actions, key = { it.id }) { action ->
                        ActionCard(
                            action = action,
                            onDelete = { onDeleteAction(action) },
                            onEdit = { onEditAction(action) },
                            onSend = { onSendAction(action) },
                        )
                    }
                }
            }
        }
        is ActionsViewModel.UiState.Error -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = stringResource(R.string.error_loading_actions),
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = { /* TODO: retry */ }) {
                        Text(stringResource(R.string.retry))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionCard(
    action: ActionsViewModel.ActionItem,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onSend: () -> Unit,
    onClick: (() -> Unit)? = null,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick ?: {},
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = action.info.title,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                Row {
                    IconButton(onClick = onEdit) {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = stringResource(R.string.edit),
                        )
                    }
                    IconButton(onClick = onSend) {
                        Icon(
                            imageVector = Icons.Default.Send,
                            contentDescription = stringResource(R.string.send),
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.delete),
                        )
                    }
                }
            }
            Text(
                text = action.info.prompt,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 8.dp),
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
