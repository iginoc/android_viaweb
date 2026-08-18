package com.igino.android_viaweb

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.view.accessibility.AccessibilityEvent

class RemoteControlService : AccessibilityService() {

    companion object {
        var instance: RemoteControlService? = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun dispatchClick(x: Float, y: Float) {
        val clickPath = Path()
        clickPath.moveTo(x, y)
        clickPath.lineTo(x, y)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(clickPath, 0, 50))
        dispatchGesture(gestureBuilder.build(), null, null)
    }

    fun dispatchSwipe(x1: Float, y1: Float, x2: Float, y2: Float, duration: Long) {
        val swipePath = Path()
        swipePath.moveTo(x1, y1)
        swipePath.lineTo(x2, y2)
        val gestureBuilder = GestureDescription.Builder()
        gestureBuilder.addStroke(GestureDescription.StrokeDescription(swipePath, 0, duration))
        dispatchGesture(gestureBuilder.build(), null, null)
    }
}
