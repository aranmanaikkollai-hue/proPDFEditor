package com.propdf.core.domain.logger

interface ProPDFLogger {
    fun d(message: String, vararg args: Any?)
    fun i(message: String, vararg args: Any?)
    fun w(message: String, vararg args: Any?)
    fun e(t: Throwable? = null, message: String, vararg args: Any?)
}
