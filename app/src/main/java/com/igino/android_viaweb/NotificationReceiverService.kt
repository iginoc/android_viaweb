package com.igino.android_viaweb

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.app.Notification
import android.util.Log

class NotificationReceiverService : NotificationListenerService() {

    data class NotificationData(
        val title: String?,
        val text: String?,
        val packageName: String,
        val timestamp: Long
    )

    companion object {
        private val _notifications = mutableListOf<NotificationData>()
        val notifications: List<NotificationData>
            get() = synchronized(_notifications) { ArrayList(_notifications) }

        fun addNotification(notification: NotificationData) {
            synchronized(_notifications) {
                _notifications.add(0, notification)
                if (_notifications.size > 10) {
                    _notifications.removeAt(10)
                }
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val extras = sbn.notification.extras
        val title = extras.getString(Notification.EXTRA_TITLE)
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
        val packageName = sbn.packageName
        val timestamp = sbn.postTime

        if (title != null || text != null) {
            addNotification(NotificationData(title, text, packageName, timestamp))
        }
    }
}
