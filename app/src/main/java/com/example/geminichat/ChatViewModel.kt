package com.example.geminichat

import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class ChatMessage(
    val text: String,
    val isUser: Boolean
)

data class ChatSession(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val messages: List<ChatMessage> = emptyList(),
    val date: Long = System.currentTimeMillis()
) {
    val formattedDate: String
        get() = SimpleDateFormat("MM/dd HH:mm", Locale.getDefault()).format(Date(date))
}

class ChatViewModel : ViewModel() {
    private val apiKey = BuildConfig.GEMINI_API_KEY
    
    private val generativeModel = GenerativeModel(
        modelName = "gemini-2.5-flash",
        apiKey = apiKey
    )

    private val _sessions = mutableStateListOf<ChatSession>()
    val sessions: List<ChatSession> = _sessions

    private val _currentSessionId = mutableStateOf<String?>(null)
    val currentSessionId: String? get() = _currentSessionId.value

    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private var chat = generativeModel.startChat()

    fun createNewSession() {
        if (_messages.isNotEmpty() && _currentSessionId.value != null) {
            updateCurrentSessionInList()
        }
        _messages.clear()
        _currentSessionId.value = UUID.randomUUID().toString()
        chat = generativeModel.startChat()
    }

    fun selectSession(sessionId: String) {
        if (_currentSessionId.value == sessionId) return
        
        // Save current session before switching
        if (_currentSessionId.value != null) {
            updateCurrentSessionInList()
        }

        val session = _sessions.find { it.id == sessionId } ?: return
        _messages.clear()
        _messages.addAll(session.messages)
        _currentSessionId.value = sessionId
        
        // Reconstruct chat history for the SDK
        val history = session.messages.map { 
            content(role = if (it.isUser) "user" else "model") { text(it.text) }
        }
        chat = generativeModel.startChat(history = history)
    }

    fun deleteSession(sessionId: String) {
        _sessions.removeAll { it.id == sessionId }
        if (_currentSessionId.value == sessionId) {
            _messages.clear()
            _currentSessionId.value = null
            chat = generativeModel.startChat()
        }
    }

    private fun updateCurrentSessionInList() {
        val currentId = _currentSessionId.value ?: return
        val index = _sessions.indexOfFirst { it.id == currentId }
        val currentSession = _sessions.getOrNull(index)
        
        val newSession = ChatSession(
            id = currentId,
            title = currentSession?.title ?: "New Chat",
            messages = _messages.toList(),
            date = currentSession?.date ?: System.currentTimeMillis()
        )
        if (index != -1) {
            _sessions[index] = newSession
        } else {
            _sessions.add(0, newSession)
        }

        // If it's a new session and has at least one message, summarize the title
        if ((currentSession == null || currentSession.title == "New Chat") && _messages.isNotEmpty()) {
            summarizeTitle(currentId, _messages.first().text)
        }
    }

    private fun summarizeTitle(sessionId: String, firstMessage: String) {
        viewModelScope.launch {
            try {
                val prompt = "以下のメッセージの内容を30文字以内で要約してタイトルにしてください。余計な説明は不要です：\n$firstMessage"
                val response = generativeModel.generateContent(prompt)
                val summary = response.text?.trim()?.removeSurrounding("\"") ?: firstMessage.take(20)
                
                val index = _sessions.indexOfFirst { it.id == sessionId }
                if (index != -1) {
                    _sessions[index] = _sessions[index].copy(title = summary)
                }
            } catch (e: Exception) {
                // Keep the default title on error
            }
        }
    }

    fun sendMessage(text: String, onResponse: (String) -> Unit = {}) {
        if (text.isBlank()) return
        
        if (_currentSessionId.value == null) {
            _currentSessionId.value = UUID.randomUUID().toString()
        }

        _messages.add(ChatMessage(text, true))
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val response = chat.sendMessage(text)
                val responseText = response.text ?: "Error: No response"
                _messages.add(ChatMessage(responseText, false))
                updateCurrentSessionInList()
                onResponse(responseText)
            } catch (e: Exception) {
                _messages.add(ChatMessage("Error: ${e.localizedMessage}", false))
            } finally {
                _isLoading.value = false
            }
        }
    }
}
