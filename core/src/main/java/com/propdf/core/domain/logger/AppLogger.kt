package com.propdf.core.domain.logger

interface AppLogger {
    fun d(tag: String, message: String)
    fun e(tag: String, message: String, throwable: Throwable? = null)
    fun i(tag: String, message: String)
    fun w(tag: String, message: String)
}

class DefaultAppLogger : AppLogger {
    override fun d(tag: String, message: String) = android.util.Log.d(tag, message)
    override fun e(tag: String, message: String, throwable: Throwable?) = android.util.Log.e(tag, message, throwable)
    override fun i(tag: String, message: String) = android.util.Log.i(tag, message)
    override fun w(tag: String, message: String) = android.util.Log.w(tag, message)
}
