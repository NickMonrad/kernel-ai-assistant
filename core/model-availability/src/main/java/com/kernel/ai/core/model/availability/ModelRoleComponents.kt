package com.kernel.ai.core.model.availability

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.KernelModel

/**
 * Shared shell for one titled section on the Models screen: a title, one summary line, then
 * the section's content. Not tied to [KernelModel], so the speech and voice sections and the
 * setup wizard (#1855) use it too.
 */
@Composable
fun ModelSectionShell(
    title: String,
    summary: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content()
        Spacer(Modifier.height(8.dp))
    }
}

/**
 * One role's section on the Models screen: "Using: …" and why, then rows grouped into
 * In use / Optional / Not needed on this phone, with "More models" collapsed.
 *
 * @param availabilityOf maps a model to the availability state the row shows.
 * @param onSelect called when a selectable chat model is chosen. Null = the role has no radio.
 * @param onAutoSelect called when "Choose automatically" is chosen. Null = no auto row.
 * @param onPrimary the row's main action (Download, Update, Cancel, Retry, Sign in…). The
 *   caller decides what it means from the state.
 * @param canDelete whether a ready row may offer Delete.
 * @param onDelete called when Delete is tapped; the caller confirms when deletion breaks the app.
 */
@Composable
fun RoleSectionView(
    section: ModelRoleSection,
    availabilityOf: (KernelModel) -> ModelAvailabilityState,
    onSelect: ((KernelModel) -> Unit)?,
    onAutoSelect: (() -> Unit)?,
    onPrimary: (KernelModel) -> Unit,
    canDelete: (KernelModel) -> Boolean,
    onDelete: (KernelModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val role = section.role
    val recommendedForTier = section.rows.firstOrNull { it.recommended }?.model
    var moreExpanded by rememberSaveable(role.name) { mutableStateOf(false) }

    ModelSectionShell(
        title = ModelRoleCopy.roleTitle(role),
        summary = ModelRoleCopy.roleSummary(role),
        modifier = modifier,
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp)) {
            Text(
                text = ModelRoleCopy.usingLine(section.using),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            ModelRoleCopy.usingNote(role, section.using, section.preferred, recommendedForTier)?.let { note ->
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        val choiceModifier = if (onSelect != null) Modifier.selectableGroup() else Modifier
        Column(modifier = choiceModifier) {
            if (onAutoSelect != null) {
                ChoiceRow(
                    title = ModelRoleCopy.AUTO_CHOICE_TITLE,
                    supporting = ModelRoleCopy.AUTO_CHOICE_SUPPORTING,
                    selected = section.preferred == null,
                    onClick = onAutoSelect,
                )
            }

            listOf(ModelGroup.IN_USE, ModelGroup.OPTIONAL, ModelGroup.NOT_NEEDED).forEach { group ->
                val rows = section.rows.filter { it.group == group }
                if (rows.isEmpty()) return@forEach
                GroupLabel(ModelRoleCopy.groupTitle(group)!!)
                rows.forEach { row ->
                    ModelRoleRowItem(
                        model = row.model,
                        state = availabilityOf(row.model),
                        required = row.required,
                        recommended = row.recommended,
                        allowedOnPhone = row.allowedOnPhone,
                        selected = if (onSelect != null) section.preferred == row.model && row.selectable else null,
                        onSelect = if (onSelect != null && row.selectable) ({ onSelect(row.model) }) else null,
                        onPrimary = onPrimary,
                        canDelete = canDelete(row.model),
                        onDelete = onDelete,
                    )
                }
            }
        }

        // ── More models (collapsed) ────────────────────────────────────────
        val more = section.rows.filter { it.group == ModelGroup.MORE_MODELS }
        if (more.isNotEmpty()) {
            TextButton(
                onClick = { moreExpanded = !moreExpanded },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(
                    if (moreExpanded) ModelRoleCopy.HIDE_MORE_MODELS
                    else "${ModelRoleCopy.MORE_MODELS_TITLE} · ${ModelRoleCopy.moreModelsCount(more.size)}",
                )
            }
            if (moreExpanded) {
                Column(modifier = choiceModifier) {
                    more.forEach { row ->
                        ModelRoleRowItem(
                            model = row.model,
                            state = availabilityOf(row.model),
                            required = false,
                            recommended = row.recommended,
                            allowedOnPhone = row.allowedOnPhone,
                            selected = if (onSelect != null) section.preferred == row.model && row.selectable else null,
                            onSelect = if (onSelect != null && row.selectable) ({ onSelect(row.model) }) else null,
                            onPrimary = onPrimary,
                            canDelete = canDelete(row.model),
                            onDelete = onDelete,
                        )
                    }
                }
            }
        }
    }
}

/**
 * A read-only section with one status line and one action that opens the owning settings
 * page. Used for speech recognition and voice output, which are managed in Voice settings.
 */
@Composable
fun StatusSectionView(
    title: String,
    summary: String,
    statusText: String,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ModelSectionShell(title = title, summary = summary, modifier = modifier) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onAction,
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "$actionLabel: $title" },
                ) {
                    Text(actionLabel)
                }
            }
        }
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 0.dp).semantics { heading() },
    )
}

@Composable
private fun ChoiceRow(
    title: String,
    supporting: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * One model row: plain-English purpose as the headline, technical name and size as secondary
 * text, a Required tag where it applies, and one primary action plus an optional Delete.
 *
 * @param selected null when the role has no radio; otherwise whether this row is the chosen one.
 * @param onSelect null when the row cannot be chosen (the radio is shown disabled).
 */
@Composable
private fun ModelRoleRowItem(
    model: KernelModel,
    state: ModelAvailabilityState,
    required: Boolean,
    recommended: Boolean,
    allowedOnPhone: Boolean,
    selected: Boolean?,
    onSelect: (() -> Unit)?,
    onPrimary: (KernelModel) -> Unit,
    canDelete: Boolean,
    onDelete: (KernelModel) -> Unit,
) {
    val ready = state is ModelAvailabilityState.Ready
    val primaryLabel = primaryLabelFor(state)
    val selectableModifier = if (selected != null) {
        Modifier
            .selectable(
                selected = selected,
                enabled = onSelect != null,
                role = Role.RadioButton,
                onClick = onSelect ?: {},
            )
            .semantics {
                if (!allowedOnPhone) stateDescription = ModelRoleCopy.NOT_ON_THIS_PHONE
            }
    } else {
        Modifier
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth().then(selectableModifier),
                verticalAlignment = Alignment.Top,
            ) {
                if (selected != null) {
                    RadioButton(
                        selected = selected,
                        onClick = null,
                        enabled = onSelect != null,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ModelRoleCopy.purpose(model),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = ModelRoleCopy.technicalLine(model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (required) {
                        Text(
                            text = ModelRoleCopy.REQUIRED_TAG,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    if (recommended && !ready) {
                        Text(
                            text = ModelRoleCopy.RECOMMENDED_NOTE,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                StateBadge(state = state)
            }

            if (state is ModelAvailabilityState.Preparing) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            val showDelete = canDelete && ready
            if (primaryLabel != null || showDelete) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (primaryLabel != null) {
                        Button(
                            onClick = { onPrimary(model) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "$primaryLabel ${model.displayName}" },
                        ) {
                            Text(primaryLabel)
                        }
                    }
                    if (showDelete) {
                        OutlinedButton(
                            onClick = { onDelete(model) },
                            modifier = Modifier
                                .weight(1f)
                                .semantics { contentDescription = "${ModelRoleCopy.DELETE_ACTION} ${model.displayName}" },
                        ) {
                            Text(ModelRoleCopy.DELETE_ACTION)
                        }
                    }
                }
            }
        }
    }
}

private fun primaryLabelFor(state: ModelAvailabilityState): String? = when (state) {
    ModelAvailabilityState.NotDisplayed -> ModelRoleCopy.DOWNLOAD_ACTION
    is ModelAvailabilityState.Preparing -> if (state.isAutoQueued) null else ModelRoleCopy.CANCEL_ACTION
    else -> defaultActionLabel(state)
}

/**
 * The "Still needed" card at the top of the Models screen, and the same state the wizard and
 * welcome show. Lists each required role that is not ready, with one Download action per row
 * that starts every missing file in that role. When nothing is missing it collapses to the
 * "All set" line with a tick.
 */
@Composable
fun StillNeededCard(
    items: List<StillNeededItem>,
    onDownload: (KernelModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = if (items.isEmpty()) {
            MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
        } else {
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
        },
    ) {
        if (items.isEmpty()) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary,
                )
                Spacer(Modifier.width(8.dp))
                Text(ModelRoleCopy.ALL_SET_TEXT, style = MaterialTheme.typography.bodyMedium)
            }
            return@Surface
        }
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = ModelRoleCopy.STILL_NEEDED_TITLE,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.error,
            )
            items.forEach { item ->
                Spacer(Modifier.height(8.dp))
                Text(
                    text = ModelRoleCopy.stillNeededLabel(item),
                    style = MaterialTheme.typography.bodyMedium,
                )
                when (item.status) {
                    RoleStatus.DOWNLOADING -> {
                        Spacer(Modifier.height(4.dp))
                        val progress = item.progress
                        if (progress != null && progress > 0f) {
                            LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    RoleStatus.MISSING -> if (item.downloadModels.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { item.downloadModels.forEach(onDownload) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(ModelRoleCopy.DOWNLOAD_ACTION)
                        }
                    }
                    RoleStatus.READY -> Unit
                }
            }
        }
    }
}
