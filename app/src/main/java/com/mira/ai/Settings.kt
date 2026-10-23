package com.mira.ai

import android.content.Context

data class Cfg(val apiKey: String, val model: String, val plannerModel: String)

object Settings {
    private const val PREFS = "mira_settings"
    const val DEFAULT_MODEL = "gemini-2.5-flash"

    fun load(ctx: Context): Cfg {
        val sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Cfg(
            apiKey = sp.getString("api_key", "") ?: "",
            model = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL,
            plannerModel = sp.getString("planner", DEFAULT_MODEL) ?: DEFAULT_MODEL
        )
    }

    fun save(ctx: Context, c: Cfg) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("api_key", c.apiKey.trim())
            .putString("model", c.model.trim().ifEmpty { DEFAULT_MODEL })
            .putString("planner", c.plannerModel.trim().ifEmpty { DEFAULT_MODEL })
            .apply()
    }
}
