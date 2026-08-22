package com.propdf.core.domain.logger

import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TimberLogger @Inject constructor() : ProPDFLogger {
    override fun d(message: String, vararg args: Any?) = Timber.d(message, *args)
    override fun i(message: String, vararg args: Any?) = Timber.i(message, *args)
    override fun w(message: String, vararg args: Any?) = Timber.w(message, *args)
    override fun e(t: Throwable?, message: String, vararg args: Any?) = Timber.e(t, message, *args)
}
