package com.example.cookierunbot.automation

import android.content.Context

object BotOptionsStore {
    private const val PREFERENCES = "bot_options"

    fun save(context: Context, options: BotOptions) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("fast_start", options.useFastStart)
            .putBoolean("cookie_relay", options.useCookieRelay)
            .putBoolean("post_relay", options.postCookieRelaySequence)
            .putBoolean("desired_boost", options.useDesiredRandomBoost)
            .putString("boost", options.desiredBoost.name)
            .putBoolean("relic", options.detectRelic)
            .putBoolean("friend_life", options.sendFriendLife)
            .putBoolean("quick_lives", options.receiveSendLivesAfterEachGame)
            .putBoolean("save_debug_images", options.saveDebugImages)
            .putString("jump_mode", options.jumpMode.name)
            .putInt("fixed_jump_count", options.fixedJumpCount)
            .putInt("tap_interval_ms", options.tapIntervalMs)
            .apply()
    }

    fun load(context: Context): BotOptions {
        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        val boost = enumValueOrDefault(
            preferences.getString("boost", null),
            BoostChoice.MAGNETIC,
        )
        val jumpMode = enumValueOrDefault(
            preferences.getString("jump_mode", null),
            JumpMode.NONE,
        )
        val cookieRelay = preferences.getBoolean("cookie_relay", false)
        return runCatching {
            BotOptions(
                useFastStart = preferences.getBoolean("fast_start", false),
                useCookieRelay = cookieRelay,
                postCookieRelaySequence = cookieRelay && preferences.getBoolean("post_relay", false),
                useDesiredRandomBoost = preferences.getBoolean("desired_boost", false),
                desiredBoost = boost,
                detectRelic = preferences.getBoolean("relic", false),
                sendFriendLife = preferences.getBoolean("friend_life", false),
                receiveSendLivesAfterEachGame = preferences.getBoolean("quick_lives", false),
                saveDebugImages = preferences.getBoolean("save_debug_images", false),
                jumpMode = jumpMode,
                fixedJumpCount = if (jumpMode == JumpMode.FIXED_COUNT) {
                    preferences.getInt("fixed_jump_count", 10)
                } else {
                    0
                },
                tapIntervalMs = if (jumpMode == JumpMode.NONE) {
                    250
                } else {
                    preferences.getInt("tap_interval_ms", 250)
                },
            )
        }.getOrDefault(BotOptions())
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default
}
