package org.fossify.messages.models

data class SearchResult(
    val messageId: Long,
    val title: String,
    val snippet: String,
    val date: String,
    val threadId: Long,
    var photoUri: String,
    // epoch millis, for the 送信済み list's day headers (0 where unused)
    val timestamp: Long = 0L,
)
