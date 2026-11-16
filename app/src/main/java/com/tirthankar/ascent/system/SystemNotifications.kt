package com.tirthankar.ascent.system

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tirthankar.ascent.MainActivity
import com.tirthankar.ascent.R
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/** The System's notifications (Tasks 38 and 39). Silent during quiet hours. */
@Singleton
class SystemNotifications @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    fun createChannel() {
        val manager = context.getSystemService(NotificationManager::class.java)
        // Channel sounds can't change after creation: Phase 3's chime needs a new channel.
        manager.deleteNotificationChannel(OLD_CHANNEL_ID)
        val chime = Uri.parse("android.resource://${context.packageName}/${com.tirthankar.ascent.designsystem.R.raw.sfx_system_chime}")
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notification_channel_description)
            setSound(chime, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build())
        }
        manager.createNotificationChannel(channel)
    }

    fun canPost(): Boolean =
        (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled()

    fun questIssued(objectives: Int, expiresAt: Instant, silent: Boolean) {
        post(
            QUEST_ISSUED_ID,
            context.getString(R.string.notification_quest_issued_title),
            context.resources.getQuantityString(R.plurals.notification_quest_issued_body, objectives, objectives, time(expiresAt)),
            silent,
        )
    }

    fun reminder(done: Int, total: Int, dayEndsAt: Instant, silent: Boolean) {
        post(
            REMINDER_ID,
            context.getString(R.string.notification_reminder_title),
            context.getString(R.string.notification_reminder_body, done, total, time(dayEndsAt)),
            silent,
        )
    }

    /** An emergency quest (Phase 3): open for 2 hours. */
    fun emergency(title: String, endsAt: Instant, silent: Boolean) {
        post(EMERGENCY_ID, context.getString(R.string.notification_emergency_title), context.getString(R.string.notification_emergency_body, title, time(endsAt)), silent)
    }

    /** A System notice from the reset (a Gate closed, a penalty, a boss stirring). */
    fun notice(header: String, body: String, kindId: Int, silent: Boolean) {
        post(NOTICE_BASE_ID + kindId, "◆ " + header.uppercase(), body, silent)
    }

    private fun post(id: Int, title: String, body: String, silent: Boolean) {
        if (!canPost()) return
        val open = PendingIntent.getActivity(
            context,
            id,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_system)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setSilent(silent)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (_: SecurityException) {
            // Permission was revoked between the check and the post: nothing to do.
        }
    }

    private fun time(instant: Instant): String = TIME.format(instant.atZone(ZoneId.systemDefault()))

    private companion object {
        const val OLD_CHANNEL_ID = "system"
        const val CHANNEL_ID = "system_v2"
        const val QUEST_ISSUED_ID = 1
        const val REMINDER_ID = 2
        const val EMERGENCY_ID = 3
        const val NOTICE_BASE_ID = 100
        val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    }
}
