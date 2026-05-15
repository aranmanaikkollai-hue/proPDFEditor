package com.propdf.core.domain.logger

import android.util.Log
import javax.inject.Inject
import javax.inject.Singleton

interface AppLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
}

@Singleton
class DefaultAppLogger @Inject constructor() : AppLogger {
    override fun d(tag: String, message: String) {
        Log.d(tag, message)
    }
    override fun e(tag: String, message: String, throwable: Throwable?) {
        Log.e(tag, message, throwable)
    }
    override fun i(tag: String, message: String) {
        Log.i(tag, message)
    }
    override fun w(tag: String, message: String) {
        Log.w(tag, message)
    }
}
