package com.poria.base.util

import android.os.StrictMode

/**
 * StrictMode 配置。只在 debug 构建里由 [com.poria.base.BaseApp] 调用。
 *
 * 只开 `penaltyLog()`：
 * 原实现带了 `penaltyDeath()` + `penaltyDialog()`，主线程任何一次磁盘读写都会直接杀掉进程，
 * 而且它是个 public 方法，release 里误调就是线上崩溃。
 */
object StrictModeConfig {

    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectNetwork() // 主线程做网络 IO
                .detectCustomSlowCalls() // 自定义的慢函数
                .detectDiskReads() // 主线程读磁盘
                .detectDiskWrites() // 主线程写磁盘
                .penaltyLog()
                .build()
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedSqlLiteObjects()
                .detectLeakedClosableObjects()
                .detectActivityLeaks()
                .penaltyLog()
                .build()
        )
    }
}
