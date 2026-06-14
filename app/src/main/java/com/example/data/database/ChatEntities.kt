package com.example.data.database

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "chat_sessions")
data class ChatSession(
    @PrimaryKey val id: String, // Unique UUID for the session
    val title: String,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String = "Smart",
    val isPinned: Boolean = false,
    val isArchived: Boolean = false
)

@Entity(tableName = "chat_messages")
data class ChatMessage(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val role: String, // "user" or "model"
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    val imageBase64: String? = null // For storing image attachment base64 locally
)
