package com.kernel.ai.feature.chat

import androidx.compose.ui.unit.dp

/**
 * Bottom clearance for scrollable content under a screen's FAB column: the three-FAB stack (40 + 12 + 40 + 12 + 56dp)
 * plus its 16dp Scaffold margin. Shared by Chats and Actions so the two lists clear the same stack.
 */
internal val FabStackClearance = 40.dp + 12.dp + 40.dp + 12.dp + 56.dp + 16.dp
