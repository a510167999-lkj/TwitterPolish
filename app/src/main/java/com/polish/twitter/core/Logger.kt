package com.polish.twitter.core

import android.util.Log
import de.robv.android.xposed.XposedBridge

object Logger {
    private const val TAG = Constants.TAG

    fun d(message: String) {
        try { Log.d(TAG, message) } catch (_: Throwable) { println("[$TAG] DEBUG: $message") }
    }

    fun i(message: String) {
        try { Log.i(TAG, message) } catch (_: Throwable) { println("[$TAG] INFO: $message") }
        try { XposedBridge.log("[$TAG] $message") } catch (_: Throwable) {}
    }

    fun w(message: String, throwable: Throwable? = null) {
        try { Log.w(TAG, message, throwable) } catch (_: Throwable) { println("[$TAG] WARN: $message") }
        try {
            XposedBridge.log("[$TAG] WARN: $message")
            throwable?.let { XposedBridge.log(it) }
        } catch (_: Throwable) {}
    }

    fun e(message: String, throwable: Throwable? = null) {
        try { Log.e(TAG, message, throwable) } catch (_: Throwable) { System.err.println("[$TAG] ERROR: $message") }
        try {
            XposedBridge.log("[$TAG] ERROR: $message")
            throwable?.let { XposedBridge.log(it) }
        } catch (_: Throwable) {}
    }
}
