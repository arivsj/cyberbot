package com.cyberbot.mobile.ui.chat

import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Guarda a conversa em memoria por [TTL_MS], independente da tela.
 *
 * O ViewModel do chat morre quando o usuario navega para outra tela; este store
 * e um singleton da aplicacao, entao a conversa volta ao abrir o chat de novo
 * dentro de 5 minutos. Passado o TTL, a conversa e descartada.
 */
@Singleton
class ChatSessionStore @Inject constructor() {

    data class Snapshot(
        val conversationId: String,
        val model: String,
        val messages: List<ChatMessageUi>,
        val updatedAt: Long,
    )

    private val mutex = Mutex()
    private var snapshot: Snapshot? = null

    suspend fun read(now: Long = System.currentTimeMillis()): Snapshot? = mutex.withLock {
        val current = snapshot ?: return null
        if (now - current.updatedAt > TTL_MS) {
            snapshot = null
            return null
        }
        current
    }

    suspend fun write(value: Snapshot) = mutex.withLock {
        snapshot = value
    }

    suspend fun clear() = mutex.withLock {
        snapshot = null
    }

    companion object {
        const val TTL_MS = 5 * 60 * 1000L
    }
}
