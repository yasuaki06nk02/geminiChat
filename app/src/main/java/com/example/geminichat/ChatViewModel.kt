package com.example.geminichat

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.content
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

private val Context.dataStore by preferencesDataStore(name = "settings")

data class ChatMessage(
    val text: String,
    val isUser: Boolean,
    val bitmap: Bitmap? = null
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

class ChatViewModel(private val context: Context) : ViewModel() {
    private val ttsEnabledKey = booleanPreferencesKey("tts_enabled")
    private val modelNameKey = stringPreferencesKey("model_name")
    private val apiKeyKey = stringPreferencesKey("api_key")

    private var _apiKey = BuildConfig.GEMINI_API_KEY
    private var _modelName = "gemini-2.5-flash"
    
    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled = _isTtsEnabled.asStateFlow()

    private val _currentModel = MutableStateFlow(_modelName)
    val currentModel = _currentModel.asStateFlow()

    private val _customApiKey = MutableStateFlow(_apiKey)
    val customApiKey = _customApiKey.asStateFlow()

    private var generativeModel = GenerativeModel(
        modelName = _modelName,
        apiKey = _apiKey
    )

    private var chat = generativeModel.startChat()

    private val _sessions = mutableStateListOf<ChatSession>()
    val sessions: List<ChatSession> = _sessions

    private val _currentSessionId = mutableStateOf<String?>(null)
    val currentSessionId: String? get() = _currentSessionId.value

    private val _messages = mutableStateListOf<ChatMessage>()
    val messages: List<ChatMessage> = _messages

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    init {
        viewModelScope.launch {
            context.dataStore.data.map { it[ttsEnabledKey] ?: true }.collect {
                _isTtsEnabled.value = it
            }
        }
        viewModelScope.launch {
            context.dataStore.data.map { it[modelNameKey] ?: "gemini-2.5-flash" }.collect {
                _modelName = it
                _currentModel.value = it
                updateModel()
            }
        }
        viewModelScope.launch {
            context.dataStore.data.map { it[apiKeyKey] ?: BuildConfig.GEMINI_API_KEY }.collect {
                _apiKey = it
                _customApiKey.value = it
                updateModel()
            }
        }
    }

    private fun updateModel() {
        generativeModel = GenerativeModel(
            modelName = _modelName,
            apiKey = _apiKey
        )
        // Re-initialize chat
        val history = _messages.map {
            content(role = if (it.isUser) "user" else "model") { text(it.text) }
        }
        chat = generativeModel.startChat(history = history)
    }

    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch {
            context.dataStore.edit { it[ttsEnabledKey] = enabled }
        }
    }

    fun setModel(modelName: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[modelNameKey] = modelName }
        }
    }

    fun setApiKey(apiKey: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[apiKeyKey] = apiKey }
        }
    }

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
        
        if (_currentSessionId.value != null) {
            updateCurrentSessionInList()
        }

        val session = _sessions.find { it.id == sessionId } ?: return
        _messages.clear()
        _messages.addAll(session.messages)
        _currentSessionId.value = sessionId
        
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
            }
        }
    }

    fun sendMessage(text: String, bitmap: Bitmap? = null, onResponse: (String) -> Unit = {}) {
        if (text.isBlank() && bitmap == null) return
        
        if (_currentSessionId.value == null) {
            _currentSessionId.value = UUID.randomUUID().toString()
        }

        _messages.add(ChatMessage(text, true, bitmap))
        _isLoading.value = true

        viewModelScope.launch {
            try {
                val response = if (bitmap != null) {
                    val inputContent = content {
                        image(bitmap)
                        text(text)
                    }
                    generativeModel.generateContent(inputContent)
                } else {
                    chat.sendMessage(text)
                }

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

class ChatViewModelFactory(private val context: Context) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ChatViewModel(context) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
