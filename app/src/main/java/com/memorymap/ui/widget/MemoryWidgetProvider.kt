package com.memorymap.ui.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.memorymap.MainActivity
import com.memorymap.R
import com.memorymap.domain.repository.AuthRepository
import com.memorymap.domain.repository.LOCAL_USER_ID
import com.memorymap.domain.repository.OnThisDayRepository
import com.memorymap.domain.usecase.WidgetContent
import com.memorymap.util.MmLog
import dagger.hilt.android.AndroidEntryPoint
import java.time.LocalDate
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The home-screen widget: a glanceable count of what this day holds in earlier
 * years, so the past surfaces without the user opening anything.
 *
 * It shows a number, never a title. The archive can be locked and its records
 * private, and the home screen is visible to whoever is holding the phone, so
 * nothing that was kept behind a lock may leak into a widget. [WidgetContent]
 * turns the day's items into that count; this class only reads them and paints
 * the view.
 *
 * A widget has no UI scope of its own, so the read runs under [goAsync] on a
 * short-lived scope that is always cancelled, and the whole fetch is bounded by
 * a timeout: a widget update must finish quickly or the system stops trusting it.
 * Everything is read from the local database - no network, nothing leaves the
 * phone, so the widget works the moment it is dropped on the home screen.
 */
@AndroidEntryPoint
class MemoryWidgetProvider : AppWidgetProvider() {

    @Inject lateinit var auth: AuthRepository

    @Inject lateinit var onThisDay: OnThisDayRepository

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        val pending = goAsync()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            try {
                val state = readState()
                val views = render(context, state)
                appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
            } catch (error: Exception) {
                MmLog.e("Widget update failed", error)
            } finally {
                scope.cancel()
                pending.finish()
            }
        }
    }

    private suspend fun readState(): WidgetContent.State {
        // The whole fetch is bounded, session restore included: a widget update
        // runs under goAsync and must finish quickly or the system stops trusting
        // it, so even a slow SDK init may not be allowed to run unbounded here.
        val items = withTimeoutOrNull(READ_TIMEOUT_MS) {
            // The widget can be refreshed after the process died, so the session
            // is restored here rather than assumed to be in memory. An offline
            // install has no session and falls back to the local account, which is
            // right: the widget works with no connection at all.
            auth.restoreSession()
            val userId = auth.currentUserId.value ?: LOCAL_USER_ID
            onThisDay.items(userId, LocalDate.now())
        }
        // A timeout leaves no items to show rather than blocking the home screen;
        // the count simply reads as empty until the next refresh.
        return WidgetContent.from(items.orEmpty())
    }

    private fun render(context: Context, state: WidgetContent.State): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_memory)
        val body = when (state) {
            WidgetContent.State.Empty -> context.getString(R.string.widget_empty)
            is WidgetContent.State.OnThisDay ->
                context.resources.getQuantityString(
                    R.plurals.widget_count,
                    state.count,
                    state.count,
                )
        }
        views.setTextViewText(R.id.widget_count, body)
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
        return views
    }

    private fun openAppIntent(context: Context): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private companion object {
        // Bounded well under the system's widget budget so a slow session restore
        // can never hang the home screen.
        const val READ_TIMEOUT_MS = 4_000L
    }
}
