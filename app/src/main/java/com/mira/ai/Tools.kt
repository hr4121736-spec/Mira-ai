package com.mira.ai

import org.json.JSONArray
import org.json.JSONObject

/** Function declarations that MIRA (Gemini) can call. Coordinates are normalized 0..1000. */
object Tools {
    private fun prop(type: String, desc: String): JSONObject =
        JSONObject().put("type", type).put("description", desc)

    private fun fn(
        name: String,
        desc: String,
        props: List<Triple<String, String, String>>,
        required: List<String>
    ): JSONObject {
        val p = JSONObject()
        for ((n, t, d) in props) p.put(n, prop(t, d))
        p.put("note", prop("STRING", "اختیاری: ایک مختصر اردو جملہ، صرف اہم قدم پر (ہر قدم پر نہیں)"))
        val params = JSONObject().put("type", "OBJECT").put("properties", p)
        if (required.isNotEmpty()) {
            val r = JSONArray()
            for (x in required) r.put(x)
            params.put("required", r)
        }
        return JSONObject().put("name", name).put("description", desc).put("parameters", params)
    }

    private const val XY = "نارملائزڈ 0 سے 1000"

    val declarations: JSONArray = JSONArray().apply {
        put(
            fn(
                "tap", "اسکرین پر ایک نقطے پر ٹیپ۔",
                listOf(
                    Triple("x", "INTEGER", "افقی نقطہ، $XY"),
                    Triple("y", "INTEGER", "عمودی نقطہ، $XY")
                ),
                listOf("x", "y")
            )
        )
        put(
            fn(
                "long_press", "کسی نقطے پر دیر تک دبانا۔",
                listOf(
                    Triple("x", "INTEGER", "افقی نقطہ، $XY"),
                    Triple("y", "INTEGER", "عمودی نقطہ، $XY"),
                    Triple("duration_ms", "INTEGER", "دورانیہ ملی سیکنڈ (ڈیفالٹ 800)")
                ),
                listOf("x", "y")
            )
        )
        put(
            fn(
                "swipe",
                "انگلی کھینچنا: اسکرول، ٹائم لائن پر پلے ہیڈ ہلانا، ہینڈل گھسیٹ کر ٹرِم کرنا، ٹول بار سرکانا۔ آہستہ/درست گھسیٹنے کے لیے duration_ms بڑھاؤ۔",
                listOf(
                    Triple("x1", "INTEGER", "شروع x، $XY"),
                    Triple("y1", "INTEGER", "شروع y، $XY"),
                    Triple("x2", "INTEGER", "اختتام x، $XY"),
                    Triple("y2", "INTEGER", "اختتام y، $XY"),
                    Triple("duration_ms", "INTEGER", "دورانیہ ملی سیکنڈ، 100 سے 4000 (ڈیفالٹ 400)")
                ),
                listOf("x1", "y1", "x2", "y2")
            )
        )
        put(
            fn(
                "pinch", "دو انگلیوں سے زوم: ٹائم لائن کو فریم کی درستگی کے لیے بڑا (zoom_in) یا چھوٹا (zoom_out) کرنا۔",
                listOf(
                    Triple("x", "INTEGER", "مرکز x، $XY"),
                    Triple("y", "INTEGER", "مرکز y، $XY"),
                    Triple("direction", "STRING", "zoom_in (انگلیاں پھیلانا) یا zoom_out (سکیڑنا)")
                ),
                listOf("x", "y", "direction")
            )
        )
        put(
            fn(
                "type_text", "فی الحال فوکس والے ٹیکسٹ باکس میں متن لکھنا (پہلے باکس پر ٹیپ کرو)۔",
                listOf(Triple("text", "STRING", "لکھنے کا متن")),
                listOf("text")
            )
        )
        put(
            fn(
                "press_key", "سسٹم بٹن: back یا home۔",
                listOf(Triple("key", "STRING", "back یا home")),
                listOf("key")
            )
        )
        put(
            fn(
                "wait", "اسکرین کے بدلنے/لوڈ ہونے کا انتظار۔",
                listOf(Triple("seconds", "INTEGER", "سیکنڈ، 1 سے 10")),
                listOf("seconds")
            )
        )
        put(fn("open_capcut", "CapCut ایپ کھولنا (اگر سامنے نہ ہو)۔", emptyList(), emptyList()))
        put(
            fn(
                "make_plan",
                "گہری سوچ والا پلانر چلا کر مکمل ایڈیٹنگ پلان بنانا (سیکنڈ بہ سیکنڈ + ذہین اضافے)۔ صارف کی ہدایات مکمل ہوتے ہی ایک بار کال کرو۔",
                listOf(
                    Triple(
                        "brief", "STRING",
                        "صارف کی ہر ہدایت: ویڈیو کون سی، اسٹائل، دورانیہ، کٹس کے سیکنڈ، میوزک، ترجیحات"
                    )
                ),
                listOf("brief")
            )
        )
        put(
            fn(
                "ask_user", "صارف سے سوال پوچھ کر اس کے جواب کا انتظار۔ ضروری سوال ایک ہی بار میں اکٹھے پوچھو۔",
                listOf(Triple("question", "STRING", "اردو میں سوال")),
                listOf("question")
            )
        )
        put(
            fn(
                "request_permission",
                "Save / Export / Share / Publish سے پہلے صارف سے اجازت (ہاں/نہیں بٹن)۔ اجازت کے بغیر یہ کارروائیاں ممنوع ہیں۔",
                listOf(Triple("action", "STRING", "کون سی کارروائی اور کن ترتیبات کے ساتھ (ریزولوشن، فریم ریٹ)")),
                listOf("action")
            )
        )
        put(
            fn(
                "finish", "کام مکمل (یا آگے نہ بڑھ سکنے) پر صارف کو اردو میں خلاصہ دے کر رکنا۔",
                listOf(
                    Triple(
                        "summary", "STRING",
                        "کیا کیا، کون سے اضافی ایفیکٹس اپنی طرف سے لگائے، اور کوئی کمی/مسئلہ"
                    )
                ),
                listOf("summary")
            )
        )
    }
}
