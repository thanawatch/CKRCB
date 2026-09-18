package com.example.cookierunbot.capture

/** Lightweight transform registry used by gesture mapping without touching frame Mats. */
object ContentGeometryRegistry {
    @Volatile private var latest: ContentTransform? = null

    fun update(transform: ContentTransform) {
        latest = transform
    }

    fun current(sourceWidth: Int, sourceHeight: Int): ContentTransform? = latest?.takeIf {
        it.sourceWidth == sourceWidth && it.sourceHeight == sourceHeight
    }

    fun clear() {
        latest = null
    }
}
