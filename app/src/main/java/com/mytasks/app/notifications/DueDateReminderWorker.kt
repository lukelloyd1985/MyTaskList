package com.mytasks.app.notifications

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import com.mytasks.app.R
import com.mytasks.app.data.remote.AuthRepository

/**
 * Local, on-device fallback reminder for the currently signed-in user's own
 * assigned tasks - fires even if the server-side scheduled sweep hasn't run
 * yet or the device has no connectivity at the exact due time. Cross-user
 * "you were assigned a task" alerts are handled server-side via FCM, since
 * this worker can only see local state.
 */
@HiltWorker
class DueDateReminderWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val authRepository: AuthRepository,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: return Result.failure()
        val assigneeId = inputData.getString(KEY_ASSIGNEE_ID)

        val currentUid = authRepository.currentUser?.uid
        if (currentUid == null || currentUid != assigneeId) {
            // Task was reassigned, or user signed out, since this was scheduled.
            return Result.success()
        }

        NotificationHelper.show(
            context = applicationContext,
            notificationId = taskId.hashCode(),
            title = applicationContext.getString(R.string.notification_task_due_soon),
            body = title,
        )
        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_TITLE = "title"
        const val KEY_ASSIGNEE_ID = "assignee_id"
    }
}
