package com.mira.ai

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings as AndroidSettings
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class MainActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var statusTv: TextView
    private lateinit var keyEt: EditText
    private lateinit var modelEt: EditText
    private lateinit var plannerEt: EditText

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val cfg = Settings.load(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(24), dp(18), dp(24))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }

        fun label(t: String, size: Float = 14f, bold: Boolean = false): TextView = TextView(this).apply {
            text = t
            textSize = size
            setTextColor(Color.parseColor("#1B2530"))
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(8), 0, dp(4))
        }

        fun field(hintText: String, initial: String, password: Boolean = false): EditText = EditText(this).apply {
            setHint(hintText)
            setText(initial)
            setSingleLine(true)
            inputType = if (password) {
                InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            } else {
                InputType.TYPE_CLASS_TEXT
            }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }

        fun button(t: String, onClick: () -> Unit): Button = Button(this).apply {
            text = t
            isAllCaps = false
            setOnClickListener { onClick() }
        }

        root.addView(label("MIRA AI", 26f, true))
        root.addView(label("CapCut ویڈیو ایڈیٹنگ ایجنٹ", 15f))

        statusTv = label("", 15f, true)
        root.addView(statusTv)

        root.addView(label("1) Gemini API Key (aistudio.google.com/apikey سے مفت)", 14f, true))
        keyEt = field("AIza…", cfg.apiKey, true)
        root.addView(keyEt)

        root.addView(label("2) ماڈل (عام کام)", 14f, true))
        modelEt = field("gemini-2.5-flash", cfg.model)
        root.addView(modelEt)

        root.addView(label("3) ماڈل (پلان بنانے کے لیے؛ مضبوط ہو تو gemini-2.5-pro)", 14f, true))
        plannerEt = field("gemini-2.5-flash", cfg.plannerModel)
        root.addView(plannerEt)

        root.addView(button("💾 محفوظ کریں") {
            save()
            toast("محفوظ ہو گیا")
        })
        root.addView(button("🔑 Key ٹیسٹ کریں") { testApi() })

        root.addView(label("4) اجازتیں", 14f, true))
        root.addView(
            label(
                "اینڈرائیڈ 13+ پر پہلے: App info ➜ ⋮ ➜ \"Allow restricted settings\"۔ پھر Accessibility میں MIRA AI کو آن کریں۔ " +
                    "بیٹری میں MIRA AI کو Unrestricted رکھیں۔",
                13f
            )
        )
        root.addView(button("⚙️ App info کھولیں") {
            startActivity(
                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                    .setData(Uri.parse("package:$packageName"))
            )
        })
        root.addView(button("♿ Accessibility سیٹنگز کھولیں") {
            startActivity(Intent(AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS))
        })

        root.addView(label("5) استعمال", 14f, true))
        root.addView(
            label(
                "سروس آن ہوتے ہی تیرتی چیٹ کھل جاتی ہے۔ CapCut کھولیں، سبز \"M\" بٹن دبا کر چیٹ کھولیں، " +
                    "ویڈیو اور کٹس کے سیکنڈ بتائیں۔ Save/Export کے لیے MIRA ہمیشہ آپ سے ہاں/نہیں پوچھے گی۔",
                13f
            )
        )

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onResume() {
        super.onResume()
        val on = MiraAccessibilityService.instance != null
        statusTv.text = if (on) "✅ MIRA سروس چل رہی ہے" else "❌ MIRA سروس بند ہے (Accessibility میں آن کریں)"
        statusTv.setTextColor(Color.parseColor(if (on) "#1E7A52" else "#B03A2E"))
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun readCfg(): Cfg = Cfg(
        keyEt.text.toString(),
        modelEt.text.toString(),
        plannerEt.text.toString()
    )

    private fun save() {
        Settings.save(this, readCfg())
    }

    private fun toast(t: String) {
        Toast.makeText(this, t, Toast.LENGTH_LONG).show()
    }

    private fun testApi() {
        save()
        val c = Settings.load(this)
        if (c.apiKey.isBlank()) {
            toast("پہلے API Key ڈالیں")
            return
        }
        toast("ٹیسٹ ہو رہا ہے…")
        scope.launch {
            try {
                val body = GeminiClient.buildRequest(
                    "Reply with one short Urdu word.",
                    listOf(GeminiClient.textPart("سلام")),
                    null,
                    null,
                    c.model
                )
                val r = GeminiClient.parse(GeminiClient.generate(c.apiKey, c.model, body) { })
                toast("✅ Key کام کر رہی ہے: " + r.text.take(60))
            } catch (e: ApiException) {
                toast("❌ ${e.code}: ${e.message}")
            }
        }
    }
}
