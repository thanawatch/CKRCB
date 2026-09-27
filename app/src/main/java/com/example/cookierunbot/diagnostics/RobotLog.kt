package com.example.cookierunbot.diagnostics

import android.util.Log

/**
 * Same call signatures as android.util.Log. Own app Logcat events also reach the 24h archive.
 * This deliberately does not read other applications' system Logcat or capture every frame.
 */
object RobotLog {
    fun i(tag: String, message: String): Int = Log.i(tag, message).also {
        OvernightLogArchive.log("[I][$tag] $message")
    }
    fun d(tag: String, message: String): Int = Log.d(tag, message).also {
        OvernightLogArchive.log("[D][$tag] $message")
    }
    fun w(tag: String, message: String): Int = Log.w(tag, message).also {
        OvernightLogArchive.log("[W][$tag] $message")
    }
    fun e(tag: String, message: String): Int = Log.e(tag, message).also {
        OvernightLogArchive.log("[E][$tag] $message")
    }
    fun v(tag: String, message: String): Int = Log.v(tag, message).also {
        OvernightLogArchive.log("[V][$tag] $message")
    }
    fun i(tag: String, message: String, error: Throwable): Int = Log.i(tag, message, error).also {
        OvernightLogArchive.log("[I][$tag] $message type=${error.javaClass.simpleName}")
    }
    fun w(tag: String, message: String, error: Throwable): Int = Log.w(tag, message, error).also {
        OvernightLogArchive.log("[W][$tag] $message type=${error.javaClass.simpleName}")
    }
    fun e(tag: String, message: String, error: Throwable): Int = Log.e(tag, message, error).also {
        OvernightLogArchive.log("[E][$tag] $message type=${error.javaClass.simpleName}")
    }
}
