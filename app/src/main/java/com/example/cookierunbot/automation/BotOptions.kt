package com.example.cookierunbot.automation

enum class JumpMode { NONE, FIXED_COUNT, CONTINUOUS }
enum class SlideMode { NONE, FIXED_COUNT, CONTINUOUS }

enum class PerformanceProfile { LEGACY, REDFINGER_OPTIMIZED }

enum class BoostChoice(val displayName: String, val assetName: String) {
    DOUBLE_COINS("Double Coins", "BOOST_DOUBLE_COINS_1.png"),
    SCORE_BONUS("+15% Score Bonus", "BOOST_15P_SCORE_BONUS_1.png"),
    HP_DRAIN("-15% HP Drain", "BOOST_M15P_HP_DRAIN_1.png"),
    REVIVE("Revive Once with 80 HP", "BOOST_REVIVE_ONCE_WITH_80HP_1.png"),
    CRUSH("70% Crush Chance", "BOOST_70P_CRUSH_CHANCE_1.png"),
    BASE_SPEED("+17% Base Speed", "BOOST_17P_BASE_SPEED_1.png"),
    GOLD_COIN("Gold Coin Magic", "BOOST_GOLD_COIN_MAGIC_1.png"),
    COLLISION("-30% Collision Damage", "BOOST_M30P_COLLISION_DAMAGE_1.png"),
    POTIONS("+20% HP from Potions", "BOOST_20P_HP_FROM_POTIONS_1.png"),
    MAGNETIC("Magnetic Aura", "BOOST_MAGNETIC_AURA_1.png"),
    PIT_LIFTS("2 Pit Lifts", "BOOST_2PIT_LIFTS_1.png"),
}

data class BotOptions(
    val performanceProfile: PerformanceProfile = PerformanceProfile.LEGACY,
    val useFastStart: Boolean = false,
    val useCookieRelay: Boolean = false,
    val postCookieRelaySequence: Boolean = false,
    val useDesiredRandomBoost: Boolean = false,
    val desiredBoost: BoostChoice = BoostChoice.MAGNETIC,
    val detectRelic: Boolean = false,
    val sendFriendLife: Boolean = false,
    val friendLifeIntervalMinutes: Int = FRIEND_LIFE_DEFAULT_INTERVAL_MINUTES,
    val receiveSendLivesAfterEachGame: Boolean = false,
    val saveDebugImages: Boolean = true,
    val saveResultImages: Boolean = true,
    val jumpMode: JumpMode = JumpMode.NONE,
    val fixedJumpCount: Int = 0,
    val tapIntervalMs: Int = 250,
    val quickLivesIntervalMinutes: Int = QUICK_LIVES_DEFAULT_INTERVAL_MINUTES,
    val slideMode: SlideMode = SlideMode.NONE,
    val fixedSlideCount: Int = 0,
    val slideIntervalMs: Int = 250,
) {
    init {
        require(friendLifeIntervalMinutes in FRIEND_LIFE_MIN_INTERVAL_MINUTES..FRIEND_LIFE_MAX_INTERVAL_MINUTES) {
            "Friend Life interval must be 1-1440 minutes"
        }
        require(quickLivesIntervalMinutes in QUICK_LIVES_MIN_INTERVAL_MINUTES..QUICK_LIVES_MAX_INTERVAL_MINUTES) {
            "Receive/Send Lives interval must be 1-1440 minutes"
        }
        require(tapIntervalMs in 100..1000) { "Jump interval must be 100-1000 ms" }
        require(jumpMode != JumpMode.FIXED_COUNT || fixedJumpCount > 0) {
            "Fixed jump count must be positive"
        }
        require(slideIntervalMs in 100..1000) { "Slide interval must be 100-1000 ms" }
        require(slideMode != SlideMode.FIXED_COUNT || fixedSlideCount > 0) {
            "Fixed slide count must be positive"
        }
        require(useCookieRelay || !postCookieRelaySequence) {
            "Post Cookie Relay sequence requires Cookie Relay"
        }
    }
}


const val QUICK_LIVES_DEFAULT_INTERVAL_MINUTES = 1
const val QUICK_LIVES_MIN_INTERVAL_MINUTES = 1
const val QUICK_LIVES_MAX_INTERVAL_MINUTES = 1440

internal fun quickLivesIntervalMs(minutes: Int): Long {
    require(minutes in QUICK_LIVES_MIN_INTERVAL_MINUTES..QUICK_LIVES_MAX_INTERVAL_MINUTES) {
        "Receive/Send Lives interval must be 1-1440 minutes"
    }
    return minutes.toLong() * 60_000L
}
