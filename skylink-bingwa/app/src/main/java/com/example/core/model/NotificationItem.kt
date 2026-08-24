package com.example.core.model

data class NotificationItem(
    val id: String,
    val title: String,
    val body: String,
    val timestampMillis: Long,
    val isRead: Boolean = false,
    val deepLinkRoute: String? = null
)
