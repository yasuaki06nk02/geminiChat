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
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
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
    private val openRouterApiKeyKey = stringPreferencesKey("open_router_api_key")

    private var _apiKey = BuildConfig.GEMINI_API_KEY
    private var _openRouterApiKey = BuildConfig.OPEN_ROUTER_API_KEY
    private var _modelName = "gemini-2.5-flash"
    
    private val _isTtsEnabled = MutableStateFlow(true)
    val isTtsEnabled = _isTtsEnabled.asStateFlow()

    private val _currentModel = MutableStateFlow(_modelName)
    val currentModel = _currentModel.asStateFlow()

    private val _customApiKey = MutableStateFlow(_apiKey)
    val customApiKey = _customApiKey.asStateFlow()

    private val _openRouterApiKeyFlow = MutableStateFlow(_openRouterApiKey)
    val openRouterApiKeyFlow = _openRouterApiKeyFlow.asStateFlow()

    private var generativeModel = GenerativeModel(
        modelName = _modelName,
        apiKey = _apiKey
    )

    private var chat = generativeModel.startChat()

    private val openRouterApi: OpenRouterApi by lazy {
        val logging = HttpLoggingInterceptor().apply { level = HttpLoggingInterceptor.Level.BODY }
        val client = OkHttpClient.Builder().addInterceptor(logging).build()
        Retrofit.Builder()
            .baseUrl("https://openrouter.ai/api/v1/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(OpenRouterApi::class.java)
    }

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
        viewModelScope.launch {
            context.dataStore.data.map { it[openRouterApiKeyKey] ?: _openRouterApiKey }.collect {
                _openRouterApiKey = it
                _openRouterApiKeyFlow.value = it
            }
        }
    }

    private fun updateModel() {
        if (!isOpenRouter()) {
            generativeModel = GenerativeModel(
                modelName = _modelName,
                apiKey = _apiKey
            )
            val history = _messages.map {
                content(role = if (it.isUser) "user" else "model") { text(it.text) }
            }
            chat = generativeModel.startChat(history = history)
        }
    }

    private fun isOpenRouter(): Boolean {
        return _modelName.contains("/") || _modelName.endsWith(":free")
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

    fun setOpenRouterApiKey(apiKey: String) {
        viewModelScope.launch {
            context.dataStore.edit { it[openRouterApiKeyKey] = apiKey }
        }
    }

    fun createNewSession() {
        if (_messages.isNotEmpty() && _currentSessionId.value != null) {
            updateCurrentSessionInList()
        }
        _messages.clear()
        _currentSessionId.value = UUID.randomUUID().toString()
        if (!isOpenRouter()) chat = generativeModel.startChat()
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
        
        if (!isOpenRouter()) {
            val history = session.messages.map { 
                content(role = if (it.isUser) "user" else "model") { text(it.text) }
            }
            chat = generativeModel.startChat(history = history)
        }
    }

    fun deleteSession(sessionId: String) {
        _sessions.removeAll { it.id == sessionId }
        if (_currentSessionId.value == sessionId) {
            _messages.clear()
            _currentSessionId.value = null
            if (!isOpenRouter()) chat = generativeModel.startChat()
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
                val responseText = if (isOpenRouter()) {
                    callOpenRouter(listOf(OpenRouterMessage(role = "user", content = prompt)))
                } else {
                    generativeModel.generateContent(prompt).text
                }
                val summary = responseText?.trim()?.removeSurrounding("\"") ?: firstMessage.take(20)
                
                val index = _sessions.indexOfFirst { it.id == sessionId }
                if (index != -1) {
                    _sessions[index] = _sessions[index].copy(title = summary)
                }
            } catch (e: Exception) {
            }
        }
    }

    private suspend fun callOpenRouter(messages: List<OpenRouterMessage>): String? {
        val request = OpenRouterRequest(
            model = _modelName,
            messages = messages
        )
        val response = openRouterApi.getCompletion(
            auth = "Bearer $_openRouterApiKey",
            request = request
        )
        return response.choices.firstOrNull()?.message?.content
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
                val responseText = if (isOpenRouter()) {
                    val history = _messages.map { 
                        OpenRouterMessage(role = if (it.isUser) "user" else "assistant", content = it.text)
                    }
                    callOpenRouter(history) ?: "Error: No response"
                } else {
                    val response = if (bitmap != null) {
                        val inputContent = content {
                            image(bitmap)
                            text(text)
                        }
                        generativeModel.generateContent(inputContent)
                    } else {
                        chat.sendMessage(text)
                    }
                    response.text ?: "Error: No response"
                }

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
