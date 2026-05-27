package com.propdf.core.domain.logger

import android.util.Log
import javax.inject.Inject

class DefaultAppLogger @Inject constructor() : AppLogger {
    override fun d(tag: String, message: String): Unit = Log.d(tag, message).let {}
    override fun i(tag: String, message: String): Unit = Log.i(tag, message).let {}
    override fun w(tag: String, message: String, throwable: Throwable?): Unit =
        Log.w(tag, message, throwable).let {}
    override fun e(tag: String, message: String, throwable: Throwable?): Unit =
        Log.e(tag, message, throwable).let {}
}
