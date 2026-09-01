package com.propdf.scanner.data.processing

import android.util.Log
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Centralized OpenCV native-library availability circuit breaker.
 *
 * Problem this solves: every OpenCV-touching class in the scanner module
 * (OpenCvDocumentProcessor here, plus the dormant EdgeDetector/
 * PerspectiveCorrector/ImageEnhancer/ScanModeDetector under
 * scanner/processing/) constructs org.opencv.core.Mat directly. A Firebase
 * Test Lab Robo crawl on a real device showed this failing with
 * UnsatisfiedLinkError the first time a live camera frame reached edge
 * detection -- and because ScannerViewModel.onAnalyzedFrame only caught
 * Exception (not Error), that single native failure was a fatal, uncaught
 * crash. Without a circuit breaker, even a caught failure would repeat on
 * every subsequent camera frame -- several times per second -- for the
 * rest of the session, each one attempting the same broken native call
 * again.
 *
 * ProPDFApplication.onCreate() calls [initialize] once. Every OpenCV call
 * site then routes its native work through [runSafely] instead of calling
 * Mat(), Imgproc calls, or Utils calls directly: once a native failure is
 * observed (here, or during initialization), no call site touches native
 * OpenCV again for the remainder of the process lifetime -- they get their
 * caller-supplied fallback value immediately instead.
 */
object OpenCvAvailability {
    private const val TAG = "OpenCvAvailability"

    // AtomicBoolean, not a plain var: read from Dispatchers.Default worker
    // threads on every scanner processing call, potentially concurrently,
    // and written once from Application.onCreate() (and possibly again
    // from a worker thread the first time a native call actually fails).
    private val available = AtomicBoolean(false)
    private val initialized = AtomicBoolean(false)

    /** Call once, from Application.onCreate(). A second call is a no-op. */
    fun initialize() {
        if (!initialized.compareAndSet(false, true)) return
        Log.i(TAG, "OPENCV_INIT_START")
        val loaded = try {
            OpenCVLoader.initLocal()
        } catch (e: Throwable) {
            Log.e(TAG, "OPENCV_INIT_FAILURE: OpenCVLoader.initLocal() threw", e)
            false
        }
        available.set(loaded)
        if (loaded) {
            Log.i(TAG, "OPENCV_INIT_SUCCESS")
        } else {
            Log.e(TAG, "OPENCV_INIT_FAILURE: initLocal() returned false")
        }
    }

    fun isAvailable(): Boolean = available.get()

    /**
     * Records a native failure observed at an actual call site (belt-and-
     * braces alongside the initialize()-time check -- a device could pass
     * initLocal() yet still fail on a specific native symbol later). Only
     * the first observed failure logs; subsequent calls are silent since
     * [runSafely] will already be short-circuiting before reaching native
     * code.
     */
    fun markUnavailable(source: String, error: Throwable) {
        if (available.compareAndSet(true, false)) {
            Log.e(TAG, "OPENCV_NATIVE_FAILURE in $source -- disabling OpenCV processing for remainder of session", error)
        }
    }

    /**
     * Runs [block] only while OpenCV is currently available, returning
     * [fallback] immediately -- without touching native code -- once it
     * isn't. This is the circuit breaker: a native failure here disables
     * every future call site for the rest of the session rather than
     * re-attempting the same broken call on the next camera frame.
     *
     * Only treats UnsatisfiedLinkError/LinkageError/NoClassDefFoundError
     * (native-availability failures) as circuit-breaker trips. Any other
     * exception is rethrown to the caller's own existing handling (Mat
     * cleanup, algorithmic fallback, etc.) -- this only owns the native-
     * availability failure class, not general processing errors.
     */
    inline fun <T> runSafely(source: String, fallback: T, block: () -> T): T {
        if (!isAvailable()) return fallback
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            markUnavailable(source, e)
            fallback
        } catch (e: LinkageError) {
            markUnavailable(source, e)
            fallback
        } catch (e: NoClassDefFoundError) {
            markUnavailable(source, e)
            fallback
        }
    }

    /** Lazy fallback variant for bitmap-producing callers. */
    inline fun <T> runSafely(source: String, fallback: () -> T, block: () -> T): T {
        if (!isAvailable()) return fallback()
        return try {
            block()
        } catch (e: UnsatisfiedLinkError) {
            markUnavailable(source, e)
            fallback()
        } catch (e: LinkageError) {
            markUnavailable(source, e)
            fallback()
        } catch (e: NoClassDefFoundError) {
            markUnavailable(source, e)
            fallback()
        }
    }
}
