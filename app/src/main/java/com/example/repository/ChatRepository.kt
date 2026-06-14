package com.example.repository

import com.example.data.api.*
import com.example.data.database.ChatDao
import com.example.data.database.ChatMessage
import com.example.data.database.ChatSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withContext

class ChatRepository(private val chatDao: ChatDao) {

    val allSessions: Flow<List<ChatSession>> = chatDao.getAllSessions()

    fun getMessagesForSession(sessionId: String): Flow<List<ChatMessage>> {
        return chatDao.getMessagesForSession(sessionId)
    }

    suspend fun createNewSession(sessionId: String, title: String, mode: String = "Smart") = withContext(Dispatchers.IO) {
        chatDao.insertSession(ChatSession(id = sessionId, title = title, mode = mode))
    }

    suspend fun updateSessionMode(sessionId: String, mode: String) = withContext(Dispatchers.IO) {
        chatDao.updateSessionMode(sessionId, mode)
    }

    suspend fun updateSessionPinned(sessionId: String, isPinned: Boolean) = withContext(Dispatchers.IO) {
        chatDao.updateSessionPinned(sessionId, isPinned)
    }

    suspend fun updateSessionArchived(sessionId: String, isArchived: Boolean) = withContext(Dispatchers.IO) {
        chatDao.updateSessionArchived(sessionId, isArchived)
    }

    suspend fun updateSessionTitle(sessionId: String, title: String) = withContext(Dispatchers.IO) {
        chatDao.updateSessionTitle(sessionId, title)
    }

    suspend fun deleteSession(sessionId: String) = withContext(Dispatchers.IO) {
        chatDao.deleteSessionAndMessages(sessionId)
    }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        chatDao.clearEverything()
    }

    /**
     * Sends a message to the Gemini API, utilizing Room database for conversational memory and persistence.
     * Checks if the session needs its title regenerated and updates the session title if appropriate.
     */
    suspend fun sendMessage(
        sessionId: String,
        userText: String,
        imageBase64: String?,
        apiKey: String,
        model: String = "gemini-3.5-flash",
        mode: String = "Smart",
        isFirstMessage: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        // 1. Persist the user message to local database immediately
        val userMessage = ChatMessage(
            sessionId = sessionId,
            role = "user",
            text = userText,
            imageBase64 = imageBase64,
            timestamp = System.currentTimeMillis()
        )
        chatDao.insertMessage(userMessage)

        // If it's the first message, update the session title to a smart summary of the user's prompt
        if (isFirstMessage) {
            val shortTitle = if (userText.length > 25) {
                userText.take(22) + "..."
            } else {
                userText
            }
            chatDao.updateSessionTitle(sessionId, shortTitle)
        }

        // 2. Fetch history for this conversation to build full context memory
        val historyList = chatDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()

        // 3. Map history elements to Gemini Chat content parts
        val apiContents = mutableListOf<Content>()
        historyList.forEach { msg ->
            val parts = mutableListOf<Part>()
            parts.add(Part(text = msg.text))
            
            // If the message has an inline image, we include it
            if (msg.role == "user" && msg.imageBase64 != null) {
                parts.add(Part(inlineData = InlineData(mimeType = "image/jpeg", data = msg.imageBase64)))
            }
            
            apiContents.add(Content(parts = parts, role = if (msg.role == "user") "user" else "model"))
        }

        // 4. Construct Request with customized System Instruction specific to selected template mode
        val systemInstruction = Content(
            parts = listOf(Part(text = getSystemInstructionForMode(mode)))
        )

        val request = GenerateContentRequest(
            contents = apiContents,
            generationConfig = GenerationConfig(temperature = 0.7f),
            systemInstruction = systemInstruction
        )

        // 5. Send to Gemini
        return@withContext try {
            val response = RetrofitClient.service.generateContent(model, apiKey, request)
            val replyText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: "I had trouble generating a reply. Please try again."

            // 6. Save response into the database
            val botMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = replyText,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(botMessage)

            replyText
        } catch (e: Exception) {
            val errorMessage = "Error calling Gemini: ${e.message ?: "Unknown error"}"
            // Save the error message to DB so the user can see it in chat or we can handle it
            val botErrorMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = errorMessage,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(botErrorMessage)
            errorMessage
        }
    }

    suspend fun insertMessage(message: ChatMessage) = withContext(Dispatchers.IO) {
        chatDao.insertMessage(message)
    }

    suspend fun regenerateMessage(
        sessionId: String,
        apiKey: String,
        model: String = "gemini-3.5-flash",
        mode: String = "Smart"
    ): String = withContext(Dispatchers.IO) {
        val historyList = chatDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()
        if (historyList.isEmpty()) {
            return@withContext "No conversation history found to regenerate."
        }

        val lastMsg = historyList.last()
        var updatedHistoryList = historyList
        if (lastMsg.role == "model") {
            chatDao.deleteMessageById(lastMsg.id)
            updatedHistoryList = chatDao.getMessagesForSession(sessionId).firstOrNull() ?: emptyList()
        }

        if (updatedHistoryList.isEmpty()) {
            return@withContext "No user message found to regenerate."
        }

        val lastUserMsg = updatedHistoryList.last()
        if (lastUserMsg.text.startsWith("Generate image: ")) {
            val rawPrompt = lastUserMsg.text.substringAfter("Generate image: ").substringBefore(" (Style: ")
            val style = lastUserMsg.text.substringAfter(" (Style: ").substringBefore(")")
            return@withContext generateImage(sessionId, rawPrompt, style, apiKey)
        }

        val apiContents = mutableListOf<com.example.data.api.Content>()
        updatedHistoryList.forEach { msg ->
            val parts = mutableListOf<com.example.data.api.Part>()
            parts.add(com.example.data.api.Part(text = msg.text))
            if (msg.role == "user" && msg.imageBase64 != null) {
                parts.add(com.example.data.api.Part(inlineData = com.example.data.api.InlineData(mimeType = "image/jpeg", data = msg.imageBase64)))
            }
            apiContents.add(com.example.data.api.Content(parts = parts, role = if (msg.role == "user") "user" else "model"))
        }

        val systemInstruction = com.example.data.api.Content(
            parts = listOf(com.example.data.api.Part(text = getSystemInstructionForMode(mode)))
        )

        val request = com.example.data.api.GenerateContentRequest(
            contents = apiContents,
            generationConfig = com.example.data.api.GenerationConfig(temperature = 0.7f),
            systemInstruction = systemInstruction
        )

        return@withContext try {
            val response = RetrofitClient.service.generateContent(model, apiKey, request)
            val replyText = response.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text
                ?: "I had trouble generating a reply. Please try again."

            val botMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = replyText,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(botMessage)
            replyText
        } catch (e: Exception) {
            val errorMessage = "Error calling Gemini: ${e.message ?: "Unknown error"}"
            val botErrorMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = errorMessage,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(botErrorMessage)
            errorMessage
        }
    }

    suspend fun generateImage(
        sessionId: String,
        prompt: String,
        style: String,
        apiKey: String,
        model: String = "gemini-2.5-flash-image"
    ): String = withContext(Dispatchers.IO) {
        val styleSuffix = if (style.isNotBlank() && style != "Default") "in $style style, visual art masterpiece, highly detailed." else "highly detailed, 8k resolution, crisp artwork."
        val fullPrompt = "$prompt, $styleSuffix"

        val request = GenerateContentRequest(
            contents = listOf(Content(parts = listOf(Part(text = fullPrompt)))),
            generationConfig = GenerationConfig(
                imageConfig = ImageConfig(aspectRatio = "1:1", imageSize = "1K"),
                responseModalities = listOf("TEXT", "IMAGE")
            )
        )

        return@withContext try {
            val response = RetrofitClient.service.generateContent(model, apiKey, request)
            val partWithImage = response.candidates?.firstOrNull()?.content?.parts?.find { it.inlineData != null }
            val imageBase64 = partWithImage?.inlineData?.data

            if (imageBase64 != null) {
                val botMessage = ChatMessage(
                    sessionId = sessionId,
                    role = "model",
                    text = "Generated Image for prompt: \"$prompt\"",
                    imageBase64 = imageBase64,
                    timestamp = System.currentTimeMillis()
                )
                chatDao.insertMessage(botMessage)
                "Success"
            } else {
                val textPart = response.candidates?.firstOrNull()?.content?.parts?.find { it.text != null }?.text
                val errorMsg = textPart ?: "API did not return any image data candidate."
                throw Exception(errorMsg)
            }
        } catch (e: Exception) {
            val errorMessage = "Failed to generate image: ${e.message ?: "Unknown error"}"
            val botErrorMessage = ChatMessage(
                sessionId = sessionId,
                role = "model",
                text = errorMessage,
                timestamp = System.currentTimeMillis()
            )
            chatDao.insertMessage(botErrorMessage)
            errorMessage
        }
    }

    private fun getSystemInstructionForMode(mode: String): String {
        return when (mode) {
            "Lite" -> "You are RHVT AI, operating in Lite mode. Provide ultra-concise, rapid-fire responses. Strip away verbose explanations, getting right to the point. Ideal for brief, quick-fire answers."
            "Smart" -> "You are RHVT AI, operating in Smart mode. Provide balanced, highly accurate, elegant, and perfectly detailed answers. Write with structured, clean formatting, striking the ultimate balance between completeness and speed."
            "Research" -> "You are RHVT AI, operating in Research mode. Act as a world-class investigative researcher. Provide deeply comprehensive, analytical, and meticulously structured answers. Explore multiple viewpoints, explain your underlying logic in detail, document step-by-step methodologies, and maintain an academic, authoritative, yet approachable tone."
            "Coding" -> "You are RHVT AI, operating in Coding mode. You are an expert programming specialist. Write, inspect, refactor, and clean-up code with modern standards, crystal-clear comments, and ideal spacing. Always wrap code securely in markdown language blocks and present logical explanations step-by-step."
            "Creative" -> "You are RHVT AI, operating in Creative mode. Dive deep into imagination, stories, rich worldbuilding, vivid descriptions, poetry, script elements, and creative brainstorms. Let your tone be captivating, expressively written, and highly artistic."
            "Study" -> "You are RHVT AI, operating in Study mode. You are a world-class interactive academic tutor. Instead of just delivering raw direct answers, guide the user to learn. Explain complex academic subjects step-by-step, use analogies, ask thought-provoking follow-up questions, and provide encouragement."
            "Math" -> "You are RHVT AI, operating in Math mode. Solve mathematical, statistical, and logical problems with absolute precision. Clearly outline each mathematical step, double-check all calculations, state assumptions, and use clear markdown typesetting for equations."
            "Expert" -> "You are RHVT AI, operating in Expert mode. Unleash your full analytical and reasoning capabilities. Tackle difficult prompts by comprehensively decomposing them, comparing alternatives, weighing trade-offs, and delivering elite-tier, thoroughly reasoned responses."
            "Friendly" -> "You are RHVT AI, operating in Friendly mode. Be warm, approachable, charming, conversational, and highly positive. Engage using inviting, empathetic language while ensuring your responses remain extremely informative, helpful, and respectful."
            else -> "You are RHVT AI. Keep your answers helpful, highly structured, polite, and elegant."
        }
    }
}
