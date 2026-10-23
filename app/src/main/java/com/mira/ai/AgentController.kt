package com.mira.ai

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * The MIRA agent loop. Every step is stateless towards Gemini: we send the system prompt, a compact
 * text memory (chat, plan, last actions), the current screenshot and the UI element list, and the
 * model answers with exactly one tool call.
 */
class AgentController(
    private val svc: MiraAccessibilityService,
    private val ui: OverlayUi
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val transcript = ArrayDeque<String>()
    private val newMsgs = ArrayList<String>()
    private val actionLog = ArrayDeque<String>()
    private var plan = ""
    private var pending: CompletableDeferred<String>? = null
    private var exportOkUntil = 0L
    private var lastSig = ""
    private var repeatCount = 0
    private var repeatWarning = ""
    private var lastW = 1080
    private var lastH = 2400

    private val risky = Regex(
        "\\b(export|save|share|publish|upload|post)\\b|ایکسپورٹ|محفوظ|شیئر|پبلش|اپلوڈ",
        RegexOption.IGNORE_CASE
    )

    private val isRunning: Boolean
        get() = job?.isActive == true

    // ───────────────────────── chat plumbing ─────────────────────────

    fun greet() {
        val g = "السلام علیکم! میں MIRA AI ہوں۔\n" +
            "آج کون سی ویڈیو بنانی ہے؟ اسٹائل (Rampage، Velocity، Cinematic…)، دورانیہ، اور کس سیکنڈ پر کٹ چاہیے — " +
            "جو معلوم ہو بتا دیں، باقی میں خود سنبھال لوں گی۔\n" +
            "(بہتر ہے ویڈیو CapCut میں کھلی ہو؛ ورنہ میں خود کھول لوں گی۔)"
        ui.addAgent(g)
        addTranscript("MIRA: $g")
        ui.openPanel()
    }

    fun onUserMessage(text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        ui.addUser(t)
        addTranscript("صارف: $t")
        val p = pending
        if (p != null && !p.isCompleted) {
            p.complete(t)
            return
        }
        newMsgs.add(t)
        ui.closePanel()
        if (!isRunning) {
            job = scope.launch { loop() }
        }
    }

    fun stop(msg: String? = null) {
        job?.cancel()
        job = null
        pending?.cancel()
        pending = null
        ui.passThrough(false)
        ui.setRunning(false)
        if (msg != null) ui.addAgent(msg)
    }

    fun shutdown() {
        stop(null)
        scope.cancel()
    }

    private fun addTranscript(line: String) {
        transcript.addLast(line.take(500))
        while (transcript.size > 14) transcript.removeFirst()
    }

    private fun addLog(line: String) {
        actionLog.addLast(line.take(220))
        while (actionLog.size > 20) actionLog.removeFirst()
    }

    private fun finishWith(msg: String, reset: Boolean = false) {
        ui.addAgent(msg)
        ui.setStatus("")
        ui.openPanel()
        if (reset) {
            transcript.clear()
            plan = ""
            actionLog.clear()
            newMsgs.clear()
            repeatWarning = ""
            lastSig = ""
            repeatCount = 0
        }
        addTranscript("MIRA: $msg")
    }

    // ───────────────────────── main loop ─────────────────────────

    private suspend fun loop() {
        ui.setRunning(true)
        var step = 0
        var errors = 0
        try {
            while (step < 400) {
                while (ui.panelOpen) delay(400)

                val cfg = Settings.load(svc)
                if (cfg.apiKey.isBlank()) {
                    finishWith("پہلے MIRA ایپ کھول کر Gemini API Key محفوظ کریں۔")
                    return
                }

                step++
                ui.setStatus("اسکرین دیکھ رہی ہوں… ($step)")
                ui.passThrough(true)
                delay(180)
                val shot = svc.screenshot()
                val uiText = svc.describeUi(shot?.w ?: lastW, shot?.h ?: lastH)
                ui.passThrough(false)
                if (shot != null) {
                    lastW = shot.w
                    lastH = shot.h
                }

                val parts = ArrayList<JSONObject>()
                parts.add(GeminiClient.textPart(buildContext(shot, uiText)))
                if (shot != null) parts.add(GeminiClient.imagePart(shot.b64))
                newMsgs.clear()

                ui.setStatus("سوچ رہی ہوں… ($step)")
                val body = GeminiClient.buildRequest(Prompts.SYSTEM, parts, Tools.declarations, 1024, cfg.model)
                val turn = try {
                    GeminiClient.parse(
                        GeminiClient.generate(cfg.apiKey, cfg.model, body) { ui.setStatus(it) }
                    )
                } catch (e: ApiException) {
                    if (e.code == 400 || e.code == 401 || e.code == 403 || e.code == 404) {
                        finishWith("API خرابی (${e.code}): ${e.message}\nAPI Key اور ماڈل کا نام چیک کریں۔")
                        return
                    }
                    errors++
                    if (errors >= 3) {
                        finishWith("API مسلسل ناکام: ${e.message}")
                        return
                    }
                    continue
                }

                val name = turn.callName
                if (name == null) {
                    errors++
                    if (errors >= 4) {
                        finishWith("ماڈل نے کوئی کارروائی نہیں دی (${turn.finishReason}). دوبارہ ہدایت دیں۔")
                        return
                    }
                    addLog("(ماڈل نے ٹول کال نہیں کیا: ${turn.finishReason})")
                    delay(1500)
                    continue
                }
                errors = 0

                val args = turn.callArgs ?: JSONObject()
                val note = args.optString("note", "")
                if (note.isNotBlank()) ui.addAgent(note)

                val result = try {
                    execute(name, args, cfg)
                } catch (e: ApiException) {
                    "API خرابی: ${e.message}"
                }
                if (name == "finish") return

                addLog("$step. $name(${compact(args)}) → $result")

                val sig = name + args.toString().take(80) + uiText.hashCode()
                if (sig == lastSig) repeatCount++ else {
                    repeatCount = 0
                    lastSig = sig
                }
                repeatWarning = when {
                    repeatCount >= 2 ->
                        "⚠️ تم یہی قدم اسی اسکرین پر دہرا رہی ہو اور کچھ نہیں بدل رہا۔ مختلف طریقہ آزماؤ (اور نقطہ، ٹول، یا Back)۔"
                    else -> ""
                }
                if (repeatCount >= 8) {
                    finishWith("میں ایک ہی جگہ پھنس گئی ہوں اور اسکرین نہیں بدل رہی۔ براہِ کرم اسکرین دیکھ کر بتائیں کیسے آگے بڑھوں۔")
                    return
                }
                delay(700)
            }
            finishWith("حفاظتی حد (400 قدم) پوری ہو گئی، اس لیے روک دیا۔ جاری رکھنے کے لیے پیغام بھیجیں۔")
        } finally {
            ui.passThrough(false)
            ui.setRunning(false)
        }
    }

    private fun buildContext(shot: Shot?, uiText: String): String = buildString {
        appendLine("## حالیہ گفتگو")
        transcript.forEach { appendLine(it) }
        if (newMsgs.isNotEmpty()) {
            appendLine()
            appendLine("## صارف کے نئے پیغامات (ان پر فوراً عمل کرو)")
            newMsgs.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("## موجودہ پلان")
        appendLine(
            if (plan.isBlank()) "(ابھی نہیں بنا — ہدایات مکمل ہوں تو make_plan کال کرو، ورنہ ask_user)" else plan
        )
        appendLine()
        appendLine("## اب تک کی کارروائیاں (آخری ${actionLog.size})")
        actionLog.forEach { appendLine(it) }
        if (repeatWarning.isNotEmpty()) {
            appendLine()
            appendLine(repeatWarning)
        }
        appendLine()
        appendLine("## اسکرین")
        if (shot == null) appendLine("(اسکرین شاٹ نہیں مل سکا؛ صرف UI فہرست پر چلو یا wait کرو)")
        appendLine(uiText)
        appendLine("اب اگلا صرف ایک قدم چنو (ایک ٹول کال)۔")
    }

    // ───────────────────────── tools ─────────────────────────

    private fun px(v: Int, size: Int): Int = v.coerceIn(0, 1000) * size / 1000

    private fun pt(a: JSONObject, kx: String = "x", ky: String = "y"): Pair<Int, Int> =
        Pair(px(a.optInt(kx, 500), lastW), px(a.optInt(ky, 500), lastH))

    private fun compact(a: JSONObject): String {
        val sb = StringBuilder()
        val keys = a.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "note") continue
            if (sb.isNotEmpty()) sb.append(',')
            sb.append(k).append('=').append(a.opt(k).toString().take(40))
        }
        return sb.toString()
    }

    /** Hard guard: Save/Export/Share/Publish buttons are blocked until the user said yes. */
    private fun guard(xPx: Int, yPx: Int): String? {
        if (System.currentTimeMillis() < exportOkUntil) return null
        val label = svc.labelsAt(xPx, yPx, lastW, lastH)
        if (risky.containsMatchIn(label)) {
            return "BLOCKED: اس ٹیپ سے '" + label.trim().take(40) +
                "' (Save/Export/Share) چل سکتا ہے۔ پہلے request_permission کال کرو۔"
        }
        return null
    }

    private suspend fun act(block: suspend () -> Boolean): String {
        while (ui.panelOpen) delay(400)
        ui.setStatus("عمل کر رہی ہوں…")
        ui.passThrough(true)
        delay(60)
        val ok = try {
            block()
        } finally {
            ui.passThrough(false)
        }
        return if (ok) "ok" else "ناکام (جیسچر منسوخ ہوا)"
    }

    private suspend fun execute(name: String, a: JSONObject, cfg: Cfg): String {
        return when (name) {
            "tap" -> {
                val (x, y) = pt(a)
                val blocked = guard(x, y)
                if (blocked != null) blocked else act { svc.tap(x, y) }
            }
            "long_press" -> {
                val (x, y) = pt(a)
                val d = a.optInt("duration_ms", 800).coerceIn(300, 3000).toLong()
                val blocked = guard(x, y)
                if (blocked != null) blocked else act { svc.longPress(x, y, d) }
            }
            "swipe" -> {
                val (x1, y1) = pt(a, "x1", "y1")
                val (x2, y2) = pt(a, "x2", "y2")
                val d = a.optInt("duration_ms", 400).coerceIn(100, 4000).toLong()
                act { svc.swipe(x1, y1, x2, y2, d) }
            }
            "pinch" -> {
                val (x, y) = pt(a)
                val zoomIn = a.optString("direction", "zoom_in").contains("in")
                val w = lastW
                act { svc.pinch(x, y, zoomIn, w) }
            }
            "type_text" -> {
                val ok = svc.typeText(a.optString("text", ""))
                if (ok) "ok" else "کوئی فوکس والا ٹیکسٹ باکس نہیں ملا؛ پہلے باکس پر ٹیپ کرو"
            }
            "press_key" -> {
                val ok = if (a.optString("key", "back").lowercase().contains("home")) svc.pressHome() else svc.pressBack()
                if (ok) "ok" else "ناکام"
            }
            "wait" -> {
                delay(a.optInt("seconds", 1).coerceIn(1, 10) * 1000L)
                "ok"
            }
            "open_capcut" -> {
                val r = svc.openCapCut()
                delay(3000)
                r
            }
            "make_plan" -> {
                val brief = a.optString("brief", "")
                ui.setStatus("پلان بنا رہی ہوں (گہری سوچ)…")
                val parts = listOf(GeminiClient.textPart(Prompts.plannerInput(transcript.joinToString("\n"), brief)))
                val body = GeminiClient.buildRequest(Prompts.PLANNER, parts, null, 4096, cfg.plannerModel)
                val resp = GeminiClient.parse(
                    GeminiClient.generate(cfg.apiKey, cfg.plannerModel, body) { ui.setStatus(it) }
                )
                plan = resp.text.ifBlank { brief }
                ui.addAgent("📋 ایڈیٹنگ پلان:\n$plan")
                "پلان محفوظ ہو گیا؛ اب عمل شروع کرو"
            }
            "ask_user" -> {
                val q = a.optString("question", "")
                addTranscript("MIRA: $q")
                ui.addAgent(q)
                ui.openPanel()
                ui.setStatus("آپ کے جواب کا انتظار…")
                val d = CompletableDeferred<String>()
                pending = d
                val ans = try {
                    d.await()
                } finally {
                    pending = null
                }
                ui.closePanel()
                "صارف نے کہا: $ans"
            }
            "request_permission" -> {
                val what = a.optString("action", "")
                addTranscript("MIRA (اجازت): $what")
                ui.openPanel()
                ui.setStatus("اجازت کا انتظار…")
                val ok = ui.askPermission(what)
                ui.closePanel()
                if (ok) {
                    exportOkUntil = System.currentTimeMillis() + 4 * 60 * 1000L
                    "صارف نے اجازت دی (4 منٹ کے لیے Save/Export کھلا ہے)"
                } else {
                    "صارف نے اجازت نہیں دی — یہ کارروائی مت کرو"
                }
            }
            "finish" -> {
                finishWith(a.optString("summary", "").ifBlank { "کام مکمل۔" }, reset = true)
                "done"
            }
            else -> "نامعلوم ٹول: $name"
        }
    }
}
