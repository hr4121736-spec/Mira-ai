package com.mira.ai

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Floating "M" bubble + chat panel, drawn as accessibility overlays (no extra permission needed).
 * Everything here must be called on the main thread.
 */
class OverlayUi(
    private val svc: AccessibilityService,
    private val onSend: (String) -> Unit,
    private val onStop: () -> Unit
) {
    private val wm = svc.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val density = svc.resources.displayMetrics.density
    private val screenW = svc.resources.displayMetrics.widthPixels
    private val screenH = svc.resources.displayMetrics.heightPixels

    private fun dp(v: Int): Int = (v * density).toInt()

    private fun round(color: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radius.toFloat()
        }

    private fun pill(label: String, bg: Int, onClick: () -> Unit): TextView =
        TextView(svc).apply {
            text = label
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(7), dp(12), dp(7))
            background = round(bg, dp(16))
            setOnClickListener { onClick() }
        }

    private val bubble = TextView(svc)
    private val panel = LinearLayout(svc)
    private val title = TextView(svc)
    private val statusTv = TextView(svc)
    private val stopBtn: TextView = pill("⏹ روکو", 0xFFC0392B.toInt()) { onStop() }
    private val closeBtn: TextView = pill("✕", 0xFF3A4450.toInt()) { closePanel() }
    private val logBox = LinearLayout(svc)
    private val scroll = ScrollView(svc)
    private val permRow = LinearLayout(svc)
    private val permText = TextView(svc)
    private val input = EditText(svc)

    private val bubbleLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(8)
        y = dp(120)
    }

    private val panelLp = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        (screenH * 0.55f).toInt(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.BOTTOM
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
    }

    var panelOpen = false
        private set

    private var bubbleAttached = false
    private var running = false
    private var permCb: ((Boolean) -> Unit)? = null

    init {
        // ── bubble ──
        bubble.text = " M "
        bubble.setTextColor(Color.WHITE)
        bubble.textSize = 14f
        bubble.setTypeface(null, Typeface.BOLD)
        bubble.setPadding(dp(12), dp(9), dp(12), dp(9))
        bubble.background = round(0xFF1E9E6A.toInt(), dp(22))
        bubble.alpha = 0.92f
        bubble.setOnTouchListener(object : View.OnTouchListener {
            var sx = 0f
            var sy = 0f
            var ox = 0
            var oy = 0
            var moved = false
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.action) {
                    MotionEvent.ACTION_DOWN -> {
                        sx = e.rawX
                        sy = e.rawY
                        ox = bubbleLp.x
                        oy = bubbleLp.y
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - sx
                        val dy = e.rawY - sy
                        if (Math.abs(dx) > dp(6) || Math.abs(dy) > dp(6)) moved = true
                        if (moved) {
                            bubbleLp.x = ox + dx.toInt()
                            bubbleLp.y = oy + dy.toInt()
                            wm.updateViewLayout(bubble, bubbleLp)
                        }
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!moved) {
                            if (panelOpen) closePanel() else openPanel()
                        }
                    }
                }
                return true
            }
        })

        // ── panel ──
        panel.orientation = LinearLayout.VERTICAL
        val r = dp(18).toFloat()
        panel.background = GradientDrawable().apply {
            setColor(0xF2101820.toInt())
            cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
        }

        val header = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(8), dp(8), dp(4))
        }
        title.text = "MIRA AI"
        title.setTextColor(Color.WHITE)
        title.textSize = 16f
        title.setTypeface(null, Typeface.BOLD)
        statusTv.setTextColor(0xFF9AA7B5.toInt())
        statusTv.textSize = 12f
        statusTv.maxLines = 1
        statusTv.setPadding(dp(8), 0, dp(8), 0)
        stopBtn.visibility = View.GONE
        header.addView(title)
        header.addView(
            statusTv,
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        )
        header.addView(stopBtn)
        header.addView(
            closeBtn,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(6) }
        )
        panel.addView(header)

        logBox.orientation = LinearLayout.VERTICAL
        scroll.addView(logBox)
        panel.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // permission row
        permRow.orientation = LinearLayout.VERTICAL
        permRow.setPadding(dp(12), dp(8), dp(12), dp(8))
        permRow.background = round(0xFF3A2E12.toInt(), dp(10))
        permRow.visibility = View.GONE
        permText.setTextColor(Color.WHITE)
        permText.textSize = 14f
        val permBtns = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        permBtns.addView(pill("✅ ہاں، اجازت ہے", 0xFF1E9E6A.toInt()) { permCb?.invoke(true) })
        permBtns.addView(
            pill("⛔ نہیں", 0xFFC0392B.toInt()) { permCb?.invoke(false) },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(10) }
        )
        permRow.addView(permText)
        permRow.addView(permBtns)
        panel.addView(
            permRow,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { setMargins(dp(8), dp(4), dp(8), dp(4)) }
        )

        // input row
        val inRow = LinearLayout(svc).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        input.hint = "یہاں لکھیں…"
        input.setHintTextColor(0xFF7A8794.toInt())
        input.setTextColor(Color.WHITE)
        input.textSize = 15f
        input.maxLines = 3
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        input.imeOptions = EditorInfo.IME_ACTION_SEND
        input.background = round(0xFF222B35.toInt(), dp(14))
        input.setPadding(dp(12), dp(8), dp(12), dp(8))
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                send()
                true
            } else {
                false
            }
        }
        inRow.addView(input, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        inRow.addView(
            pill("بھیجیں", 0xFF2B5BD7.toInt()) { send() },
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(8) }
        )
        panel.addView(inRow)

        wm.addView(bubble, bubbleLp)
        bubbleAttached = true
    }

    private fun send() {
        val t = input.text.toString().trim()
        if (t.isEmpty()) return
        input.setText("")
        onSend(t)
    }

    private fun addMsg(text: String, mine: Boolean) {
        val tv = TextView(svc).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = 14f
            maxWidth = (screenW * 0.85f).toInt()
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = round(if (mine) 0xFF2B5BD7.toInt() else 0xFF2A323C.toInt(), dp(12))
            textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        }
        val lp = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = if (mine) Gravity.END else Gravity.START
            setMargins(dp(8), dp(3), dp(8), dp(3))
        }
        logBox.addView(tv, lp)
        if (logBox.childCount > 60) logBox.removeViewAt(0)
        scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    fun addUser(t: String) = addMsg(t, true)

    fun addAgent(t: String) = addMsg(t, false)

    fun setStatus(t: String) {
        statusTv.text = t
        bubble.text = if (t.isBlank()) " M " else " M · " + t.take(22) + " "
    }

    fun setRunning(r: Boolean) {
        running = r
        stopBtn.visibility = if (r) View.VISIBLE else View.GONE
        bubble.background = round(if (r) 0xFFE08A1E.toInt() else 0xFF1E9E6A.toInt(), dp(22))
        if (!r) setStatus("")
    }

    fun openPanel() {
        if (panelOpen) return
        panelOpen = true
        wm.addView(panel, panelLp)
    }

    fun closePanel() {
        if (!panelOpen) return
        panelOpen = false
        try {
            wm.removeView(panel)
        } catch (ignored: Exception) {
        }
    }

    /** While on, the bubble is invisible and touches/gestures/screenshots pass straight through. */
    fun passThrough(on: Boolean) {
        val f = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        bubbleLp.flags = if (on) bubbleLp.flags or f else bubbleLp.flags and f.inv()
        bubble.visibility = if (on) View.INVISIBLE else View.VISIBLE
        if (bubbleAttached) {
            try {
                wm.updateViewLayout(bubble, bubbleLp)
            } catch (ignored: Exception) {
            }
        }
    }

    suspend fun askPermission(text: String): Boolean = suspendCancellableCoroutine { cont ->
        permText.text = "🔐 اجازت درکار: $text"
        permRow.visibility = View.VISIBLE
        addMsg("🔐 اجازت درکار: $text", false)
        permCb = { ok ->
            permRow.visibility = View.GONE
            permCb = null
            if (cont.isActive) cont.resume(ok)
        }
        cont.invokeOnCancellation {
            permRow.visibility = View.GONE
            permCb = null
        }
    }

    fun destroy() {
        closePanel()
        if (bubbleAttached) {
            bubbleAttached = false
            try {
                wm.removeView(bubble)
            } catch (ignored: Exception) {
            }
        }
    }
}
