package com.example.cookierunbot.automation

import android.content.Context

object BotOptionsStore {
    private const val PREFERENCES = "bot_options"

    fun save(context: Context, options: BotOptions) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("fast_start", options.useFastStart)
            .putString("performance_profile", options.performanceProfile.name)
            .putBoolean("cookie_relay", options.useCookieRelay)
            .putBoolean("post_relay", options.postCookieRelaySequence)
            .putBoolean("desired_boost", options.useDesiredRandomBoost)
            .putString("boost", options.desiredBoost.name)
            .putBoolean("relic", options.detectRelic)
            .putBoolean("friend_life", options.sendFriendLife)
            .putInt("friend_life_interval_minutes", options.friendLifeIntervalMinutes)
            .putBoolean("quick_lives", options.receiveSendLivesAfterEachGame)
            .putInt("quick_lives_interval_minutes", options.quickLivesIntervalMinutes)
            .putBoolean("save_debug_images", options.saveDebugImages)
            .putBoolean("save_result_images", options.saveResultImages)
            .putString("jump_mode", options.jumpMode.name)
            .putInt("fixed_jump_count", options.fixedJumpCount)
            .putInt("tap_interval_ms", options.tapIntervalMs)
            .putString("slide_mode", options.slideMode.name)
            .putInt("fixed_slide_count", options.fixedSlideCount)
            .putInt("slide_interval_ms", options.slideIntervalMs)
            .putBoolean("anti_bot_image_telegram", options.sendAntiBotImagesToTelegram)
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
        val slideMode = enumValueOrDefault(
            preferences.getString("slide_mode", null),
            SlideMode.NONE,
        )
        val cookieRelay = preferences.getBoolean("cookie_relay", false)
        return runCatching {
            BotOptions(
                performanceProfile = performanceProfileFromStoredValue(
                    preferences.getString("performance_profile", null),
                ),
                useFastStart = preferences.getBoolean("fast_start", false),
                useCookieRelay = cookieRelay,
                postCookieRelaySequence = cookieRelay && preferences.getBoolean("post_relay", false),
                useDesiredRandomBoost = preferences.getBoolean("desired_boost", false),
                desiredBoost = boost,
                detectRelic = preferences.getBoolean("relic", false),
                sendFriendLife = preferences.getBoolean("friend_life", false),
                friendLifeIntervalMinutes = friendLifeIntervalMinutesFromStoredValue(
                    preferences.getInt("friend_life_interval_minutes", FRIEND_LIFE_DEFAULT_INTERVAL_MINUTES),
                ),
                receiveSendLivesAfterEachGame = preferences.getBoolean("quick_lives", false),
                quickLivesIntervalMinutes = quickLivesIntervalMinutesFromStoredValue(
                    preferences.getInt("quick_lives_interval_minutes", QUICK_LIVES_DEFAULT_INTERVAL_MINUTES),
                ),
                saveDebugImages = preferences.getBoolean("save_debug_images", true),
                saveResultImages = preferences.getBoolean("save_result_images", true),
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
                slideMode = slideMode,
                fixedSlideCount = if (slideMode == SlideMode.FIXED_COUNT) {
                    preferences.getInt("fixed_slide_count", 10)
                } else {
                    0
                },
                slideIntervalMs = if (slideMode == SlideMode.NONE) {
                    250
                } else {
                    preferences.getInt("slide_interval_ms", 250)
                },
                sendAntiBotImagesToTelegram = preferences.getBoolean(
                    "anti_bot_image_telegram",
                    false,
                ),
            )
        }.getOrDefault(BotOptions())
    }

    /** Image settings can be changed in the overlay without restarting the bot. */
    fun saveImageOptions(
        context: Context,
        saveDebugImages: Boolean,
        saveResultImages: Boolean,
        sendAntiBotImagesToTelegram: Boolean? = null,
    ) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putBoolean("save_debug_images", saveDebugImages)
            .putBoolean("save_result_images", saveResultImages)
            .apply {
                sendAntiBotImagesToTelegram?.let {
                    putBoolean("anti_bot_image_telegram", it)
                }
            }
            .apply()
    }

    fun shouldSaveNoMatchImages(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("save_debug_images", true)

    fun shouldSaveResultImages(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("save_result_images", true)

    fun shouldSendAntiBotImagesToTelegram(context: Context): Boolean =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getBoolean("anti_bot_image_telegram", false)

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String?, default: T): T =
        value?.let { runCatching { enumValueOf<T>(it) }.getOrNull() } ?: default

    internal fun friendLifeIntervalMinutesFromStoredValue(value: Int): Int =
        value.takeIf { it in FRIEND_LIFE_MIN_INTERVAL_MINUTES..FRIEND_LIFE_MAX_INTERVAL_MINUTES }
            ?: FRIEND_LIFE_DEFAULT_INTERVAL_MINUTES

    internal fun quickLivesIntervalMinutesFromStoredValue(value: Int): Int =
        value.takeIf { it in QUICK_LIVES_MIN_INTERVAL_MINUTES..QUICK_LIVES_MAX_INTERVAL_MINUTES }
            ?: QUICK_LIVES_DEFAULT_INTERVAL_MINUTES

    internal fun performanceProfileFromStoredValue(value: String?): PerformanceProfile =
        enumValueOrDefault(value, PerformanceProfile.LEGACY)
}
