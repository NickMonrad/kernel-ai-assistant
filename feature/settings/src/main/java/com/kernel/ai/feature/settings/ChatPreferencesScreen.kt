package com.kernel.ai.feature.settings

import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val RETENTION_OPTIONS = listOf(
    1 to "1 day",
    3 to "3 days",
    7 to "7 days",
    14 to "14 days",
    30 to "30 days",
    -1 to "Never",
)

private val FONT_SIZE_OPTIONS = listOf(
    0 to "Small",
    1 to "Medium",
    2 to "Large",
)

data class BubbleThemePreset(
    val key: String,
    val label: String,
    val userColor: Color,
    val assistantColor: Color,
)

private val BUBBLE_THEMES = listOf(
    BubbleThemePreset("system", "System default", Color.Unspecified, Color.Unspecified),
    BubbleThemePreset("jandal", "Jandal", Color(0xFF4F7942), Color(0xFF2D5023)),
    BubbleThemePreset("ocean", "Ocean", Color(0xFF1565C0), Color(0xFF0D47A1)),
    BubbleThemePreset("forest", "Forest", Color(0xFF2E7D32), Color(0xFF1B5E20)),
    BubbleThemePreset("sunset", "Sunset", Color(0xFFE65100), Color(0xFFBF360C)),
    BubbleThemePreset("mono", "Mono", Color(0xFF424242), Color(0xFF212121)),
    BubbleThemePreset("lavender", "Lavender", Color(0xFF7B1FA2), Color(0xFF4A148C)),
)

private val WALLPAPER_COLORS = listOf(
    Color(0xFF1A1A2E) to "Midnight",
    Color(0xFF16213E) to "Deep blue",
    Color(0xFF0F3460) to "Navy",
    Color(0xFF533483) to "Plum",
    Color(0xFF2D2D2D) to "Charcoal",
    Color(0xFF1B4332) to "Forest dark",
    Color(0xFF2D5023) to "Fern green",
    Color(0xFF1A1040) to "Paua deep",
    Color(0xFF0D7A7A) to "Paua teal",
    Color(0xFF111111) to "AMOLED black",
    Color(0xFF2C1810) to "Warm brown",
    Color(0xFF1A2332) to "Slate",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatPreferencesScreen(
    onBack: () -> Unit = {},
    viewModel: ChatPreferencesViewModel = hiltViewModel(),
) {
    val retentionDays by viewModel.archiveRetentionDays.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val bubbleTheme by viewModel.bubbleTheme.collectAsStateWithLifecycle()
    val userFontColor by viewModel.userFontColor.collectAsStateWithLifecycle()
    val assistantFontColor by viewModel.assistantFontColor.collectAsStateWithLifecycle()
    val wallpaperType by viewModel.wallpaperType.collectAsStateWithLifecycle()
    val wallpaperColor by viewModel.wallpaperColor.collectAsStateWithLifecycle()
    val wallpaperImageUri by viewModel.wallpaperImageUri.collectAsStateWithLifecycle()
    val copyToolCalls by viewModel.copyToolCalls.collectAsStateWithLifecycle()
    val copyThinking by viewModel.copyThinking.collectAsStateWithLifecycle()
    val useSystemColors by viewModel.useSystemColors.collectAsStateWithLifecycle()

    var showRetentionPicker by remember { mutableStateOf(false) }
    var showFontSizePicker by remember { mutableStateOf(false) }
    var showBubbleThemePicker by remember { mutableStateOf(false) }
    var showUserFontColorPicker by remember { mutableStateOf(false) }
    var showAssistantFontColorPicker by remember { mutableStateOf(false) }
    var showWallpaperColorPicker by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri != null) {
            // Take persistable permission so the URI survives reboots
            context.contentResolver.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            scope.launch {
                viewModel.setWallpaperType("image")
                viewModel.setWallpaperImageUri(uri.toString())
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chat Preferences") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
            // ─────── Archive ───────
            SectionHeader("Archive")
            val currentLabel = RETENTION_OPTIONS.find { it.first == retentionDays }?.second ?: "7 days"
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { showRetentionPicker = true },
                headlineContent = { Text("Auto-delete archived after") },
                supportingContent = { Text(currentLabel) },
            )
            HorizontalDivider()

            // ─────── Appearance ───────
            SectionHeader("Appearance")

            // Font size
            val fontSizeLabel = FONT_SIZE_OPTIONS.find { it.first == fontSize }?.second ?: "Medium"
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { showFontSizePicker = true },
                headlineContent = { Text("Message font size") },
                supportingContent = { Text(fontSizeLabel) },
            )

            // Bubble theme
            val themeLabel = BUBBLE_THEMES.find { it.key == bubbleTheme }?.label ?: "System default"
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { showBubbleThemePicker = true },
                headlineContent = { Text("Bubble theme") },
                supportingContent = { Text(themeLabel) },
            )

            // Dynamic Colour toggle (#1183)
            ListItem(
                modifier = Modifier.fillMaxWidth(),
                headlineContent = { Text("Use system colours") },
                supportingContent = { Text("Use Android Dynamic Colour when available. Turn off to use the Jandal theme.") },
                trailingContent = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        Switch(
                            checked = useSystemColors,
                            onCheckedChange = { viewModel.setUseSystemColors(it) },
                        )
                    }
                },
            )

            // Font colour — user bubbles
            val userColorLabel = if (userFontColor != null) {
                "Custom (#${(userFontColor!! and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')})"
            } else "Default"
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { showUserFontColorPicker = true },
                headlineContent = { Text("User bubble text colour") },
                supportingContent = { Text(userColorLabel) },
                leadingContent = {
                    if (userFontColor != null) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(userFontColor!!)),
                        )
                    }
                },
            )

            // Font colour — assistant bubbles
            val assistantColorLabel = if (assistantFontColor != null) {
                "Custom (#${(assistantFontColor!! and 0xFFFFFF).toString(16).uppercase().padStart(6, '0')})"
            } else "Default"
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable { showAssistantFontColorPicker = true },
                headlineContent = { Text("Assistant bubble text colour") },
                supportingContent = { Text(assistantColorLabel) },
                leadingContent = {
                    if (assistantFontColor != null) {
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(assistantFontColor!!)),
                        )
                    }
                },
            )

            HorizontalDivider()

            // ─────── Wallpaper ───────
            SectionHeader("Wallpaper")

            // Wallpaper type
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WallpaperTypeButton(
                    label = "None",
                    selected = wallpaperType == "none",
                    onClick = { scope.launch { viewModel.setWallpaperType("none") } },
                )
                WallpaperTypeButton(
                    label = "Colour",
                    selected = wallpaperType == "color",
                    onClick = { showWallpaperColorPicker = true },
                )
                WallpaperTypeButton(
                    label = "Image",
                    selected = wallpaperType == "image",
                    onClick = { imagePicker.launch("image/*") },
                    icon = { Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp)) },
                )
            }

            // Current wallpaper preview
            when (wallpaperType) {
                "color" -> {
                    wallpaperColor?.let { color ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(color))
                                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "Current wallpaper colour",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (Color(color).let { (it.red + it.green + it.blue) / 3 > 0.5f }) {
                                    Color.Black
                                } else {
                                    Color.White
                                },
                            )
                        }
                    }
                }
                "image" -> {
                    wallpaperImageUri?.let { uri ->
                        AsyncImage(
                            model = Uri.parse(uri),
                            contentDescription = "Wallpaper preview",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(80.dp)
                                .padding(horizontal = 16.dp)
                                .clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Crop,
                            placeholder = null,
                            error = null,
                        )
                }
                }
            }

            HorizontalDivider()

            // ─────── Conversation Copy (#1024) ───────
            SectionHeader("Conversation Copy")
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable {
                    viewModel.setCopyToolCalls(!copyToolCalls)
                },
                headlineContent = { Text("Copy tool call content") },
                supportingContent = { Text("Include tool call requests and results when copying the conversation") },
                trailingContent = {
                    Switch(
                        checked = copyToolCalls,
                        onCheckedChange = { viewModel.setCopyToolCalls(it) },
                    )
                },
            )
            ListItem(
                modifier = Modifier.fillMaxWidth().clickable {
                    viewModel.setCopyThinking(!copyThinking)
                },
                headlineContent = { Text("Copy thinking block content") },
                supportingContent = { Text("Include the assistant's thinking blocks when copying the conversation (only present when thinking is enabled)") },
                trailingContent = {
                    Switch(
                        checked = copyThinking,
                        onCheckedChange = { viewModel.setCopyThinking(it) },
                    )
                },
            )

            Spacer(Modifier.height(32.dp))
        }
    }

    // ─────── Dialogs ───────

    if (showRetentionPicker) {
        AlertDialog(
            onDismissRequest = { showRetentionPicker = false },
            title = { Text("Auto-delete archived after") },
            text = {
                Column {
                    RETENTION_OPTIONS.forEach { (days, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = days == retentionDays,
                                    onClick = {
                                        scope.launch { viewModel.setArchiveRetentionDays(days) }
                                        showRetentionPicker = false
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                scope.launch { viewModel.setArchiveRetentionDays(days) }
                                showRetentionPicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRetentionPicker = false }) { Text("Cancel") } },
        )
    }

    if (showFontSizePicker) {
        AlertDialog(
            onDismissRequest = { showFontSizePicker = false },
            title = { Text("Message font size") },
            text = {
                Column {
                    FONT_SIZE_OPTIONS.forEach { (size, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                RadioButton(
                                    selected = size == fontSize,
                                    onClick = {
                                        viewModel.setFontSize(size)
                                        showFontSizePicker = false
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.setFontSize(size)
                                showFontSizePicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showFontSizePicker = false }) { Text("Cancel") } },
        )
    }

    if (showBubbleThemePicker) {
        AlertDialog(
            onDismissRequest = { showBubbleThemePicker = false },
            title = { Text("Bubble theme") },
            text = {
                Column {
                    BUBBLE_THEMES.forEach { preset ->
                        ListItem(
                            headlineContent = { Text(preset.label) },
                            supportingContent = {
                                if (preset.key != "system") {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(preset.userColor),
                                        )
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(preset.assistantColor),
                                        )
                                    }
                                }
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = preset.key == bubbleTheme,
                                    onClick = {
                                        viewModel.setBubbleTheme(preset.key)
                                        showBubbleThemePicker = false
                                    },
                                )
                            },
                            modifier = Modifier.fillMaxWidth().clickable {
                                viewModel.setBubbleTheme(preset.key)
                                showBubbleThemePicker = false
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showBubbleThemePicker = false }) { Text("Cancel") } },
        )
    }

    if (showUserFontColorPicker) {
        ColorPickerDialog(
            title = "User bubble text colour",
            currentColor = userFontColor?.let { Color(it) },
            onColorSelected = { color ->
                viewModel.setUserFontColor(color?.toArgb()?.toLong())
                showUserFontColorPicker = false
            },
            onDismiss = { showUserFontColorPicker = false },
        )
    }

    if (showAssistantFontColorPicker) {
        ColorPickerDialog(
            title = "Assistant bubble text colour",
            currentColor = assistantFontColor?.let { Color(it) },
            onColorSelected = { color ->
                viewModel.setAssistantFontColor(color?.toArgb()?.toLong())
                showAssistantFontColorPicker = false
            },
            onDismiss = { showAssistantFontColorPicker = false },
        )
    }

    if (showWallpaperColorPicker) {
        ColorPickerDialog(
            title = "Wallpaper colour",
            currentColor = wallpaperColor?.let { Color(it) },
            presetColors = WALLPAPER_COLORS.map { it.first },
            onColorSelected = { color ->
                if (color != null) {
                    viewModel.setWallpaperType("color")
                    viewModel.setWallpaperColor(color.toArgb().toLong())
                }
                showWallpaperColorPicker = false
            },
            onDismiss = { showWallpaperColorPicker = false },
        )
    }
}

// ─────────────────── Reusable helpers ───────────────────

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
    )
}

@Composable
private fun WallpaperTypeButton(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    icon: @Composable (() -> Unit)? = null,
) {
    if (selected) {
        Button(onClick = onClick) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(4.dp))
            Text(label)
        }
    } else {
        OutlinedButton(onClick = onClick) {
            icon?.invoke()
            if (icon != null) Spacer(Modifier.width(4.dp))
            Text(label)
        }
    }
}

private val TEXT_COLORS = listOf(
    null to "Default",
    Color(0xFFFFFFFF) to "White",
    Color(0xFF000000) to "Black",
    Color(0xFFE0E0E0) to "Light gray",
    Color(0xFF424242) to "Dark gray",
    Color(0xFF2196F3) to "Blue",
    Color(0xFF4CAF50) to "Green",
    Color(0xFFFF9800) to "Orange",
    Color(0xFFE91E63) to "Pink",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ColorPickerDialog(
    title: String,
    currentColor: Color?,
    presetColors: List<Color>? = null,
    onColorSelected: (Color?) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = presetColors ?: TEXT_COLORS.mapNotNull { it.first }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                // Default (inherit) option
                ListItem(
                    headlineContent = { Text("Default") },
                    leadingContent = {
                        RadioButton(
                            selected = currentColor == null,
                            onClick = { onColorSelected(null) },
                        )
                    },
                    modifier = Modifier.fillMaxWidth().clickable { onColorSelected(null) },
                )

                // Preset colours
                if (presetColors == null) {
                    TEXT_COLORS.filter { it.first != null }.forEach { (color, label) ->
                        ListItem(
                            headlineContent = { Text(label) },
                            leadingContent = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    RadioButton(
                                        selected = currentColor?.toArgb() == color!!.toArgb(),
                                        onClick = { onColorSelected(color) },
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(20.dp)
                                            .clip(CircleShape)
                                            .background(color!!),
                                    )
                                }
                            },
                            modifier = Modifier.fillMaxWidth().clickable { onColorSelected(color) },
                        )
                    }
                } else {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp)
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        presetColors.forEach { color ->
                            val isSelected = currentColor?.toArgb() == color.toArgb()
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(color)
                                    .border(
                                        width = if (isSelected) 3.dp else 1.dp,
                                        color = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outlineVariant,
                                        shape = CircleShape,
                                    )
                                    .clickable { onColorSelected(color) },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = "Selected",
                                        tint = if (color.let { (it.red + it.green + it.blue) / 3 > 0.5f }) {
                                            Color.Black
                                        } else {
                                            Color.White
                                        },
                                        modifier = Modifier.size(18.dp),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Preview(showBackground = true)
@Composable
private fun ChatPreferencesScreenPreview() {
    MaterialTheme {
        ChatPreferencesScreen()
    }
}