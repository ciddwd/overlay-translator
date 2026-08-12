package com.gameocr.app.data

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * 按 [seconds] 重设 connect/read/write/call 超时，返回一个共享底层连接池的新 client。
 *
 * 调用一次 newBuilder + build 是廉价操作（不重建 dispatcher / connectionPool），适合每次 API
 * 请求前按 [Settings.apiTimeoutSeconds] 重新构造。
 *
 * connect 超时上限 15s（与服务器 TCP 握手不应该等太久）。read/write/call 都用 [seconds]。
 */
fun OkHttpClient.withApiTimeout(seconds: Int): OkHttpClient {
    // Remote LLM requests intentionally use twice the user-facing network timeout.
    // Other callers still pass the normal 5..300 second setting.
    val s = seconds.coerceIn(5, 600).toLong()
    val connectSec = minOf(15L, s / 2).coerceAtLeast(5L)
    return newBuilder()
        .connectTimeout(connectSec, TimeUnit.SECONDS)
        .readTimeout(s, TimeUnit.SECONDS)
        .writeTimeout(s, TimeUnit.SECONDS)
        .callTimeout(s, TimeUnit.SECONDS)
        .build()
}
