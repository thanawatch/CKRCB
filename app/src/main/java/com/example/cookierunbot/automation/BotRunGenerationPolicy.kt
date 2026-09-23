package com.example.cookierunbot.automation

internal object BotRunGenerationPolicy {
    fun isActive(running: Boolean, currentGeneration: Long, workerGeneration: Long?): Boolean =
        running && (workerGeneration == null || workerGeneration == currentGeneration)
}
