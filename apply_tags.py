#!/usr/bin/env python3
"""Apply Compose test tags to permission dialogs in ActionsScreen.kt"""

import re

path = "feature/chat/src/main/java/com/kernel/ai/feature/chat/ActionsScreen.kt"
with open(path) as f:
    content = f.read()

# === 1. Hands-free calling dialog ===

# Add modifier to AlertDialog
content = content.replace(
    "// Hands-free calling contextual permission surface\n"
    "    handsFreeCallingState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissHandsFreeCallingDialog() },",
    "// Hands-free calling contextual permission surface\n"
    "    handsFreeCallingState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissHandsFreeCallingDialog() },\n"
    "            modifier = Modifier\n"
    "                .semantics { testTagsAsResourceId = true }\n"
    "                .testTag(\"permission_dialog_hands_free_calling\"),",
)

# Add modifier to "Open dialer this time" TextButton
content = content.replace(
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingDialerFallback() }) {\n"
    "                        Text(\"Open dialer this time\")",
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingDialerFallback() },\n"
    "                        modifier = Modifier.testTag(\"permission_dialog_hands_free_open_dialer\"),\n"
    "                    ) {\n"
    "                        Text(\"Open dialer this time\")",
)

# Add modifier to "Open App Permissions" TextButton
content = content.replace(
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingOpenAppPermissions() }) {\n"
    "                        Text(\"Open App Permissions\")",
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingOpenAppPermissions() },\n"
    "                        modifier = Modifier.testTag(\"permission_dialog_hands_free_open_app_permissions\"),\n"
    "                    ) {\n"
    "                        Text(\"Open App Permissions\")",
)

# Add modifier to "Allow hands-free calling" TextButton
content = content.replace(
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingRequestPermission() }) {\n"
    "                        Text(\"Allow hands-free calling\")",
    "                    TextButton(\n"
    "                        onClick = { viewModel.onHandsFreeCallingRequestPermission() },\n"
    "                        modifier = Modifier.testTag(\"permission_dialog_hands_free_allow\"),\n"
    "                    ) {\n"
    "                        Text(\"Allow hands-free calling\")",
)

# Add modifier to "Not now" dismissButton in hands-free dialog
# Find the dismissButton within the hands-free dialog (before // Clear history)
content = content.replace(
    "            dismissButton = {\n"
    "                TextButton(\n"
    "                    onClick = { viewModel.dismissHandsFreeCallingDialog() }) {\n"
    "                    Text(\"Not now\")\n"
    "                }\n"
    "            },\n"
    "        )\n"
    "    }\n"
    "    // Clear history confirmation",
    "            dismissButton = {\n"
    "                TextButton(\n"
    "                    onClick = { viewModel.dismissHandsFreeCallingDialog() },\n"
    "                    modifier = Modifier.testTag(\"permission_dialog_hands_free_not_now\"),\n"
    "                ) {\n"
    "                    Text(\"Not now\")\n"
    "                }\n"
    "            },\n"
    "        )\n"
    "    }\n"
    "    // Clear history confirmation",
)

# === 2. DND dialog ===

# Add modifier to DND AlertDialog
content = content.replace(
    "    // DND special-access contextual surface\n"
    "    dndState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissDndDialog() },",
    "    // DND special-access contextual surface\n"
    "    dndState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissDndDialog() },\n"
    "            modifier = Modifier\n"
    "                .semantics { testTagsAsResourceId = true }\n"
    "                .testTag(\"permission_dialog_dnd\"),",
)

# Add modifier to DND "Open DND access settings" TextButton
content = content.replace(
    "                TextButton(onClick = { viewModel.onDndOpenSettings() }) {\n"
    "                    Text(\"Open DND access settings\")",
    "                TextButton(\n"
    "                    onClick = { viewModel.onDndOpenSettings() },\n"
    "                    modifier = Modifier.testTag(\"permission_dialog_dnd_open_settings\"),\n"
    "                ) {\n"
    "                    Text(\"Open DND access settings\")",
)

# Add modifier to DND "Not now" TextButton
content = content.replace(
    "                TextButton(onClick = { viewModel.dismissDndDialog() }) {\n"
    "                    Text(\"Not now\")",
    "                TextButton(\n"
    "                    onClick = { viewModel.dismissDndDialog() },\n"
    "                    modifier = Modifier.testTag(\"permission_dialog_dnd_not_now\"),\n"
    "                ) {\n"
    "                    Text(\"Not now\")",
)

# === 3. Write-settings dialog ===

# Add modifier to write-settings AlertDialog
content = content.replace(
    "    // Write-settings special-access contextual surface\n"
    "    writeSettingsState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissWriteSettingsDialog() },",
    "    // Write-settings special-access contextual surface\n"
    "    writeSettingsState?.let { state ->\n"
    "        AlertDialog(\n"
    "            onDismissRequest = { viewModel.dismissWriteSettingsDialog() },\n"
    "            modifier = Modifier\n"
    "                .semantics { testTagsAsResourceId = true }\n"
    "                .testTag(\"permission_dialog_write_settings\"),",
)

# Add modifier to write-settings "Open settings access" TextButton
content = content.replace(
    "                TextButton(onClick = { viewModel.onWriteSettingsOpenSettings() }) {\n"
    "                    Text(\"Open settings access\")",
    "                TextButton(\n"
    "                    onClick = { viewModel.onWriteSettingsOpenSettings() },\n"
    "                    modifier = Modifier.testTag(\"permission_dialog_write_settings_open_settings\"),\n"
    "                ) {\n"
    "                    Text(\"Open settings access\")",
)

# Add modifier to write-settings "Not now" TextButton
content = content.replace(
    "                TextButton(onClick = { viewModel.dismissWriteSettingsDialog() }) {\n"
    "                    Text(\"Not now\")",
    "                TextButton(\n"
    "                    onClick = { viewModel.dismissWriteSettingsDialog() },\n"
    "                    modifier = Modifier.testTag(\"permission_dialog_write_settings_not_now\"),\n"
    "                ) {\n"
    "                    Text(\"Not now\")",
)

with open(path, "w") as f:
    f.write(content)

# Verify
count = content.count("testTagsAsResourceId")
print(f"testTagsAsResourceId occurrences: {count}")
tag_count = len(re.findall(r'testTag\("permission_dialog_', content))
print(f"permission_dialog_* testTag occurrences: {tag_count}")
