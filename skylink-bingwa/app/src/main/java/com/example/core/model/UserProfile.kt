package com.example.core.model

data class UserProfile(
    val name: String = "",
    val primaryNumber: String = "",
    val isOnboardingCompleted: Boolean = false,
    val notificationsEnabled: Boolean = false,
    val offersPromotionsEnabled: Boolean = true,
    val helpfulRemindersEnabled: Boolean = true,
    val personalisedMessagesEnabled: Boolean = true,
    val appUpdatesEnabled: Boolean = true
)
