package com.elewashy.nexa.ui.components.common

import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Process-local bridge for transient messages emitted outside Compose UI boundaries.
 * Prefer a screen-owned [androidx.compose.material3.SnackbarHostState] when one is available.
 */
object AppMessages {
    internal data class Message(
        val message: String,
        val actionLabel: String? = null,
        val duration: SnackbarDuration = SnackbarDuration.Long,
    )

    private val channel = Channel<Message>(
        capacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    internal val messages = channel.receiveAsFlow()

    fun show(
        message: String,
        actionLabel: String? = null,
        duration: SnackbarDuration = SnackbarDuration.Long,
    ) {
        if (message.isNotBlank()) channel.trySend(Message(message, actionLabel, duration))
    }
}
