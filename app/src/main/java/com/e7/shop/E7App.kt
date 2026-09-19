package com.e7.shop

import android.app.Application
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 应用入口：只做一件事 —— 把**未捕获异常**落盘。
 *
 * 为什么需要（2026-09-18 审查结论）：
 * 引擎循环里的异常会被 BotEngine/AiBotEngine 的 catch (Exception) 记进运行日志，
 * 但**循环之外的崩溃**（悬浮窗的主线程回调、服务生命周期、Compose 重组，
 * 以及 Error 子类如 OutOfMemoryError —— 它们根本不是 Exception）只进 logcat，
 * 而 logcat 是环形缓冲区，过夜挂机后必然被冲掉。
 * 玩家第二天问"为什么半夜停了"，手上没有任何证据 —— 这与 RunLog 当初存在的理由完全一样。
 *
 * 因此这里补上最后一道网：任何未捕获异常都追加到
 * Android/data/com.e7.shop/files/e7sa_crash.log（adb 可直接 pull），
 * 然后照常交回系统默认处理器（该崩还是要崩，不掩盖问题）。
 */
class E7App : Application() {

    override fun onCreate() {
        super.onCreate()
        installCrashLogger()
    }

    private fun installCrashLogger() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            try {
                writeCrash(thread.name, error)
            } catch (ignored: Throwable) {
                // 崩溃处理器自身绝不能再抛异常，否则会掩盖原始崩溃
            }
            Log.e(TAG, "uncaught exception in " + thread.name, error)
            previous?.uncaughtException(thread, error)
        }
    }

    private fun writeCrash(threadName: String, error: Throwable) {
        val dir = getExternalFilesDir(null) ?: return
        val f = File(dir, CRASH_FILE)
        // 反复崩溃时只保留最近一份，避免把存储写满
        if (f.exists() && f.length() > MAX_CRASH_BYTES) f.delete()
        val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        f.appendText(
            stamp + "  thread=" + threadName + "\n" +
                error.javaClass.name + ": " + error.message + "\n" +
                error.stackTraceToString() + "\n"
        )
    }

    private companion object {
        const val TAG = "E7SA.Crash"
        const val CRASH_FILE = "e7sa_crash.log"
        const val MAX_CRASH_BYTES = 1L * 1024 * 1024
    }
}
