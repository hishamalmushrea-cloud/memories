package com.memorymap.util

/**
 * The only logging entry point in the app.
 *
 * Rule enforced here: coordinates, free text, emails and media paths are user
 * content and must never reach Logcat in a release build. `d()` and `v()` are
 * stripped by the ProGuard rules in `app/proguard-rules.pro`; `w()` and `e()`
 * stay but only ever receive exception types and messages, never payloads.
 */
object MmLog {

    private const val TAG = "MemoryMap"

    fun d(message: String) {
        android.util.Log.d(TAG, message)
    }

    fun v(message: String) {
        android.util.Log.v(TAG, message)
    }

    fun w(message: String, error: Throwable? = null) {
        android.util.Log.w(TAG, message, error)
    }

    fun e(message: String, error: Throwable? = null) {
        android.util.Log.e(TAG, message, error)
    }
}
