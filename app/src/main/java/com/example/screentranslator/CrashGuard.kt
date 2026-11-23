package com.example.screentranslator

import android.app.Application
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScreenTranslatorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashGuard.install(this)
    }
}

object CrashGuard {

    private const val TAG = "ScreenTranslatorGuard"
    private const val CRASH_LOG_FILE = "last_crash_report.txt"
    private const val MAX_LOG_BYTES = 8 * 1024

    @Volatile
    private var appFilesDir: File? = null

    fun install(application: Application) {
        appFilesDir = application.applicationContext.filesDir
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            recordError("Uncaught:${thread.name}", throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }
    }

    fun coroutineHandler(
        tag: String,
        onError: ((Throwable) -> Unit)? = null
    ): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            if (throwable is CancellationException) return@CoroutineExceptionHandler
            recordError(tag, throwable)
            runCatching { onError?.invoke(throwable) }
        }
    }

    inline fun <T> safeCall(tag: String, fallback: T, block: () -> T): T {
        return try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordError(tag, t)
            fallback
        }
    }

    inline fun safeRun(tag: String, block: () -> Unit) {
        try {
            block()
        } catch (ce: CancellationException) {
            throw ce
        } catch (t: Throwable) {
            recordError(tag, t)
        }
    }

    fun recordError(tag: String, throwable: Throwable) {
        if (throwable is CancellationException) return
        Log.e(TAG, "[$tag] ${throwable.javaClass.simpleName}: ${throwable.message}", throwable)
        val dir = appFilesDir ?: return
        try {
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            val text = "[$timestamp] [$tag]\n$sw".take(MAX_LOG_BYTES)
            File(dir, CRASH_LOG_FILE).writeText(text, Charsets.UTF_8)
        } catch (_: Throwable) {
            // Never allow diagnostics logging itself to throw
        }
    }
}
