package com.mytasks.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import java.util.Date
import com.mytasks.app.R
import com.mytasks.app.data.model.TaskItem
import com.mytasks.app.data.model.TaskPriority
import com.mytasks.app.ui.theme.PriorityHigh
import com.mytasks.app.ui.theme.PriorityLow
import com.mytasks.app.ui.theme.PriorityMedium
import com.mytasks.app.util.DateTimeUtils

@Composable
fun TaskRow(
    task: TaskItem,
    onToggleComplete: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    // Only supplied by the caller for reorderable (open) rows - carries
    // the actual drag-gesture detection, so this composable stays
    // presentation-only and doesn't need to know how reordering works.
    dragHandleModifier: Modifier? = null,
) {
    Surface(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = task.completed, onCheckedChange = onToggleComplete)

            PriorityDot(priority = task.priority, modifier = Modifier.padding(end = 10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.completed) TextDecoration.LineThrough else null,
                    color = if (task.completed) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (task.assigneeName.isNotBlank()) {
                        Icon(
                            Icons.Filled.Person,
                            contentDescription = null,
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = task.assigneeName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 4.dp, end = 8.dp),
                        )
                    }
                    val due = task.dueAt
                    if (due != null) {
                        val overdue = !task.completed && DateTimeUtils.isOverdue(due, Date())
                        Text(
                            text = DateTimeUtils.formatDueDate(due),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (overdue) PriorityHigh else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (task.notify) {
                            Icon(
                                Icons.Filled.NotificationsActive,
                                contentDescription = stringResource(R.string.cd_reminder_set),
                                modifier = Modifier
                                    .padding(start = 4.dp)
                                    .size(14.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }

            if (dragHandleModifier != null) {
                Icon(
                    Icons.Filled.DragHandle,
                    contentDescription = stringResource(R.string.cd_drag_to_reorder),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .padding(start = 4.dp)
                        .then(dragHandleModifier),
                )
            }
        }
    }
}

@Composable
fun PriorityDot(priority: TaskPriority, modifier: Modifier = Modifier) {
    val color = when (priority) {
        TaskPriority.HIGH -> PriorityHigh
        TaskPriority.MEDIUM -> PriorityMedium
        TaskPriority.LOW -> PriorityLow
    }
    Surface(
        modifier = modifier
            .size(10.dp)
            .clip(CircleShape),
        color = color,
    ) {}
}
