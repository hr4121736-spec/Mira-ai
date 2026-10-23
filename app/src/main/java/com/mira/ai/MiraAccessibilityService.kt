package com.mira.ai

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.util.Base64
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.resume

data class Shot(val b64: String, val w: Int, val h: Int)

class MiraAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MiraAccessibilityService? = null
    }

    private var ui: OverlayUi? = null
    private var controller: AgentController? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        if (ui != null) return
        val u = OverlayUi(
            this,
            { text -> controller?.onUserMessage(text) },
            { controller?.stop("⏹ روک دیا گیا۔") }
        )
        ui = u
        val c = AgentController(this, u)
        controller = c
        c.greet()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        controller?.shutdown()
        ui?.destroy()
        ui = null
        controller = null
        instance = null
        return super.onUnbind(intent)
    }

    // ───────────────────────── gestures ─────────────────────────

    private suspend fun dispatch(g: GestureDescription): Boolean =
        suspendCancellableCoroutine { cont ->
            val ok = dispatchGesture(
                g,
                object : GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
                null
            )
            if (!ok && cont.isActive) cont.resume(false)
        }

    suspend fun tap(x: Int, y: Int): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, 60))
            .build()
        return dispatch(g)
    }

    suspend fun longPress(x: Int, y: Int, durationMs: Long): Boolean {
        val p = Path().apply { moveTo(x.toFloat(), y.toFloat()) }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        return dispatch(g)
    }

    suspend fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long): Boolean {
        val p = Path().apply {
            moveTo(x1.toFloat(), y1.toFloat())
            lineTo(x2.toFloat(), y2.toFloat())
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        return dispatch(g)
    }

    suspend fun pinch(cx: Int, cy: Int, zoomIn: Boolean, screenW: Int): Boolean {
        val near = screenW * 0.06f
        val far = screenW * 0.28f
        val s = if (zoomIn) near else far
        val e = if (zoomIn) far else near
        val maxX = (screenW - 2).toFloat()
        val cyf = cy.toFloat()
        val p1 = Path().apply {
            moveTo((cx - s).coerceIn(2f, maxX), cyf)
            lineTo((cx - e).coerceIn(2f, maxX), cyf)
        }
        val p2 = Path().apply {
            moveTo((cx + s).coerceIn(2f, maxX), cyf)
            lineTo((cx + e).coerceIn(2f, maxX), cyf)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p1, 0, 450))
            .addStroke(GestureDescription.StrokeDescription(p2, 0, 450))
            .build()
        return dispatch(g)
    }

    fun pressBack(): Boolean = performGlobalAction(GLOBAL_ACTION_BACK)

    fun pressHome(): Boolean = performGlobalAction(GLOBAL_ACTION_HOME)

    fun typeText(text: String): Boolean {
        val node = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    fun openCapCut(): String {
        val pkgs = listOf("com.lemon.lvoverseas", "com.lemon.lv")
        for (p in pkgs) {
            val i = packageManager.getLaunchIntentForPackage(p)
            if (i != null) {
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                return try {
                    startActivity(i)
                    "CapCut کھولنے کی کوشش کی ($p)"
                } catch (e: Exception) {
                    "CapCut کھل نہ سکا: ${e.message}"
                }
            }
        }
        return "CapCut انسٹال نظر نہیں آیا"
    }

    // ───────────────────────── screenshot ─────────────────────────

    private suspend fun grab(): Bitmap? = suspendCancellableCoroutine { cont ->
        try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                mainExecutor,
                object : TakeScreenshotCallback {
                    override fun onSuccess(screenshot: ScreenshotResult) {
                        var out: Bitmap? = null
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(
                                screenshot.hardwareBuffer,
                                screenshot.colorSpace
                            )
                            out = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        } catch (ignored: Exception) {
                        } finally {
                            try {
                                screenshot.hardwareBuffer.close()
                            } catch (ignored: Exception) {
                            }
                        }
                        if (cont.isActive) cont.resume(out)
                    }

                    override fun onFailure(errorCode: Int) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        } catch (e: Exception) {
            if (cont.isActive) cont.resume(null)
        }
    }

    private fun encode(bmp: Bitmap): Shot {
        val w = bmp.width
        val h = bmp.height
        val scale = 1280f / maxOf(w, h).toFloat()
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (w * scale).toInt(), (h * scale).toInt(), true)
        } else {
            bmp
        }
        val bos = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, bos)
        return Shot(Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP), w, h)
    }

    /** Takes a screenshot (retrying if Android's rate limit rejects it). Returns null if impossible. */
    suspend fun screenshot(): Shot? {
        for (attempt in 0 until 3) {
            val bmp = grab()
            if (bmp != null) return withContext(Dispatchers.Default) { encode(bmp) }
            delay(450)
        }
        return null
    }

    // ───────────────────────── screen reading ─────────────────────────

    /** Compact text list of visible elements with normalized (0..1000) center points. */
    fun describeUi(w: Int, h: Int): String {
        val root = rootInActiveWindow ?: return "(UI فہرست دستیاب نہیں؛ صرف اسکرین شاٹ دیکھو)"
        val sb = StringBuilder()
        sb.append("ایپ پیکیج: ").append(root.packageName).append('\n')
        var count = 0
        val r = Rect()

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || count >= 70 || depth > 30) return
            if (n.isVisibleToUser) {
                val t = (n.text ?: n.contentDescription)?.toString()?.trim()?.take(40)
                if (!t.isNullOrEmpty() || n.isClickable) {
                    n.getBoundsInScreen(r)
                    if (r.width() > 0 && r.height() > 0) {
                        val cx = r.centerX().coerceIn(0, w) * 1000 / w
                        val cy = r.centerY().coerceIn(0, h) * 1000 / h
                        sb.append("- ")
                        sb.append(if (t.isNullOrEmpty()) "(بغیر نام)" else "\"" + t + "\"")
                        sb.append(" (").append(cx).append(',').append(cy).append(')')
                        if (n.isClickable) sb.append(" [ٹیپ ہو سکتا]")
                        sb.append('\n')
                        count++
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)
        if (count == 0) sb.append("(کوئی متن والا عنصر نہیں ملا؛ اسکرین شاٹ پر بھروسا کرو)\n")
        return sb.toString()
    }

    /** Labels (text, description, view id) of small elements that contain the given pixel. Used by the Save/Export guard. */
    fun labelsAt(x: Int, y: Int, w: Int, h: Int): String {
        val root = rootInActiveWindow ?: return ""
        val sb = StringBuilder()
        val r = Rect()
        val maxArea = w.toLong() * h.toLong() / 7

        fun walk(n: AccessibilityNodeInfo?, depth: Int) {
            if (n == null || depth > 30) return
            n.getBoundsInScreen(r)
            val inside = r.contains(x, y)
            if (inside) {
                val area = r.width().toLong() * r.height().toLong()
                if (area in 1..maxArea) {
                    n.text?.let { sb.append(it).append(' ') }
                    n.contentDescription?.let { sb.append(it).append(' ') }
                    n.viewIdResourceName?.let {
                        sb.append(it.substringAfterLast('/').replace('_', ' ')).append(' ')
                    }
                }
            }
            for (i in 0 until n.childCount) walk(n.getChild(i), depth + 1)
        }

        walk(root, 0)
        return sb.toString()
    }
}
