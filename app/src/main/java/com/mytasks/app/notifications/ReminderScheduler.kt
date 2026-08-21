package com.mytasks.app.notifications

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import com.mytasks.app.data.model.TaskItem

/** How long before the due time the on-device reminder fires. */
private const val REMINDER_LEAD_MINUTES = 30L

@Singleton
class ReminderScheduler @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context,
) {
    private val workManager get() = WorkManager.getInstance(context)

    fun schedule(task: TaskItem) {
        val uniqueName = uniqueWorkName(task.id)
        val dueAt = task.dueAt
        if (!task.notify || dueAt == null || task.completed || task.assigneeId.isBlank()) {
            cancel(task.id)
            return
        }

        val reminderAt = dueAt.time - TimeUnit.MINUTES.toMillis(REMINDER_LEAD_MINUTES)
        val fireAt = if (reminderAt > System.currentTimeMillis()) reminderAt else dueAt.time
        val delayMillis = fireAt - System.currentTimeMillis()
        if (delayMillis <= 0) {
            cancel(task.id)
            return
        }

        val data = Data.Builder()
            .putString(DueDateReminderWorker.KEY_TASK_ID, task.id)
            .putString(DueDateReminderWorker.KEY_TITLE, task.title)
            .putString(DueDateReminderWorker.KEY_ASSIGNEE_ID, task.assigneeId)
            .build()

        val request = OneTimeWorkRequestBuilder<DueDateReminderWorker>()
            .setInitialDelay(delayMillis, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .build()

        workManager.enqueueUniqueWork(uniqueName, ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork(uniqueWorkName(taskId))
    }

    private fun uniqueWorkName(taskId: String) = "reminder_$taskId"
}
