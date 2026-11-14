package com.kernel.ai.core.model.availability

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kernel.ai.core.inference.download.KernelModel

/**
 * One role's section: header ("Using: …" + why), then rows grouped into
 * In use / Optional / Not needed on this phone, with "More models" collapsed.
 *
 * Reused by the Models screen, the setup wizard (#1855) and the welcome (#1849).
 *
 * @param availabilityOf maps a model to the availability state the row shows.
 * @param onSelect called when a ready chat model is tapped. Null = rows are not selectable.
 * @param onAutoSelect called when "Choose automatically" is tapped. Null = no auto row.
 * @param onPrimary the row's main action (Download, Cancel, Retry, Sign in…). The caller
 *   decides what it means from the state.
 * @param canDelete whether a ready row may offer Delete.
 * @param onDelete called when Delete is tapped; the caller confirms if the delete would break the role.
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

    Column(modifier = modifier.fillMaxWidth()) {
        // ── Header ─────────────────────────────────────────────────────────
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = ModelRoleCopy.roleTitle(role),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = ModelRoleCopy.roleSummary(role),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = ModelRoleCopy.usingLine(role, section.using),
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

        if (onAutoSelect != null) {
            ChoiceRow(
                title = ModelRoleCopy.AUTO_CHOICE_TITLE,
                supporting = ModelRoleCopy.AUTO_CHOICE_SUPPORTING,
                selected = section.preferred == null,
                onClick = onAutoSelect,
            )
        }

        // ── Groups ─────────────────────────────────────────────────────────
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
                    selected = if (onSelect != null) section.using == row.model else null,
                    onSelect = onSelect,
                    onPrimary = onPrimary,
                    canDelete = canDelete(row.model),
                    onDelete = onDelete,
                )
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
                    if (moreExpanded) "Hide ${ModelRoleCopy.MORE_MODELS_TITLE.lowercase()}"
                    else "${ModelRoleCopy.MORE_MODELS_TITLE} · ${ModelRoleCopy.moreModelsCount(more.size)}",
                )
            }
            if (moreExpanded) {
                more.forEach { row ->
                    ModelRoleRowItem(
                        model = row.model,
                        state = availabilityOf(row.model),
                        required = false,
                        recommended = row.recommended,
                        selected = if (onSelect != null) section.using == row.model else null,
                        onSelect = onSelect,
                        onPrimary = onPrimary,
                        canDelete = canDelete(row.model),
                        onDelete = onDelete,
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 0.dp),
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
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
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
 */
@Composable
fun ModelRoleRowItem(
    model: KernelModel,
    state: ModelAvailabilityState,
    required: Boolean,
    recommended: Boolean,
    selected: Boolean?,
    onSelect: ((KernelModel) -> Unit)?,
    onPrimary: (KernelModel) -> Unit,
    canDelete: Boolean,
    onDelete: (KernelModel) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ready = state is ModelAvailabilityState.Ready
    val primaryLabel = primaryLabelFor(state)
    val rowClick = if (onSelect != null && ready) ({ onSelect(model) }) else null

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (rowClick != null) Modifier.clickable(onClick = rowClick) else Modifier),
                verticalAlignment = Alignment.Top,
            ) {
                if (selected != null) {
                    RadioButton(
                        selected = selected,
                        onClick = rowClick,
                        enabled = rowClick != null,
                    )
                    Spacer(Modifier.width(4.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = ModelRoleCopy.purpose(model),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        text = ModelRoleCopy.technicalLine(model),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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

            if (primaryLabel != null || (canDelete && ready)) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (primaryLabel != null) {
                        Button(
                            onClick = { onPrimary(model) },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(primaryLabel)
                        }
                    }
                    if (canDelete && ready) {
                        OutlinedButton(
                            onClick = { onDelete(model) },
                            modifier = if (primaryLabel == null) Modifier.fillMaxWidth() else Modifier,
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
    ModelAvailabilityState.Ready -> null
    is ModelAvailabilityState.Preparing -> if (state.isAutoQueued) null else ModelRoleCopy.CANCEL_ACTION
    ModelAvailabilityState.NotDisplayed -> ModelRoleCopy.DOWNLOAD_ACTION
    else -> defaultActionLabel(state)
}

/**
 * The "Still needed" card at the top of the Models screen, and the same state the wizard and
 * welcome show. Lists each required role that is not ready, with one action per row.
 * When nothing is missing it collapses to the "All set" line with a tick.
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
                    RoleStatus.MISSING -> item.downloadModel?.let { model ->
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { onDownload(model) },
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
