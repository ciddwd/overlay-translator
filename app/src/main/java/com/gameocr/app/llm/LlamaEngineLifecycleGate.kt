package com.gameocr.app.llm

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Serializes every operation that reads or mutates the process-global llama.cpp state.
 *
 * The Android binding owns one model, context and chat-template instance per process. A session
 * therefore covers model readiness checks, prompt metrics and inference, while unload uses the
 * same gate. Keeping this primitive separate makes the lifecycle ordering testable without JNI.
 */
internal class LlamaEngineLifecycleGate {
    private val mutex = Mutex()

    suspend fun <T> withSession(action: suspend () -> T): T = mutex.withLock {
        action()
    }
}
