package com.example.geminichat

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.geminichat.ui.theme.GeminiChatTheme
import kotlinx.coroutines.launch
import java.util.Locale

class MainActivity : ComponentActivity(), TextToSpeech.OnInitListener {
    private lateinit var tts: TextToSpeech
    private var isTtsReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        tts = TextToSpeech(this, this)

        setContent {
            GeminiChatTheme {
                ChatScreen(
                    onSpeak = { text -> speak(text) }
                )
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale.JAPANESE
            isTtsReady = true
        }
    }

    private fun speak(text: String) {
        if (isTtsReady) {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        tts.stop()
        tts.shutdown()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onSpeak: (String) -> Unit
) {
    val context = LocalContext.current
    val viewModel: ChatViewModel = viewModel(factory = ChatViewModelFactory(context))
    
    val messages = viewModel.messages
    val sessions = viewModel.sessions
    val currentSessionId = viewModel.currentSessionId
    val isLoading by viewModel.isLoading.collectAsState()
    val isTtsEnabled by viewModel.isTtsEnabled.collectAsState()
    val currentModel by viewModel.currentModel.collectAsState()
    val customApiKey by viewModel.customApiKey.collectAsState()
    val openRouterApiKey by viewModel.openRouterApiKeyFlow.collectAsState()

    var inputText by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var selectedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    var showSettings by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    // Image Picker Launcher
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        selectedImageUri = uri
        if (uri != null) {
            selectedBitmap = if (Build.VERSION.SDK_INT < 28) {
                MediaStore.Images.Media.getBitmap(context.contentResolver, uri)
            } else {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                ImageDecoder.decodeBitmap(source)
            }
        }
    }

    // Speech to Text Launcher
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val data = result.data
            val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spokenText = results?.get(0) ?: ""
            if (spokenText.isNotBlank()) {
                viewModel.sendMessage(spokenText) { response ->
                    if (isTtsEnabled) onSpeak(response)
                }
            }
        }
    }

    // Permission Launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE.toString())
            }
            speechLauncher.launch(intent)
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Spacer(Modifier.height(12.dp))
                NavigationDrawerItem(
                    label = { Text("新しいチャット") },
                    selected = false,
                    onClick = {
                        viewModel.createNewSession()
                        scope.launch { drawerState.close() }
                    },
                    icon = { Icon(Icons.Default.Add, contentDescription = null) },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                Text(
                    "履歴",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleSmall
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(sessions) { session ->
                        NavigationDrawerItem(
                            label = {
                                Column {
                                    Text(session.title, maxLines = 1, style = MaterialTheme.typography.bodyLarge)
                                    Text(session.formattedDate, style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                                }
                            },
                            selected = session.id == currentSessionId,
                            onClick = {
                                viewModel.selectSession(session.id)
                                scope.launch { drawerState.close() }
                            },
                            icon = { Icon(Icons.Default.Chat, contentDescription = null) },
                            badge = {
                                IconButton(onClick = { viewModel.deleteSession(session.id) }) {
                                    Icon(
                                        Icons.Default.Delete,
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            },
                            modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text("Gemini Chat") },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Menu")
                        }
                    },
                    actions = {
                        IconButton(onClick = { showSettings = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            },
            bottomBar = {
                Surface(tonalElevation = 3.dp) {
                    Column(modifier = Modifier.navigationBarsPadding().imePadding()) {
                        if (selectedImageUri != null) {
                            Box(modifier = Modifier.padding(8.dp).size(100.dp)) {
                                selectedBitmap?.let {
                                    Image(
                                        bitmap = it.asImageBitmap(),
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                                IconButton(
                                    onClick = { selectedImageUri = null; selectedBitmap = null },
                                    modifier = Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha = 0.5f), CircleShape).size(24.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Remove", tint = Color.White, modifier = Modifier.size(16.dp))
                                }
                            }
                        }
                        
                        Row(
                            modifier = Modifier.padding(8.dp).fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = {
                                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                                    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.JAPANESE.toString())
                                    }
                                    speechLauncher.launch(intent)
                                } else {
                                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                }
                            }) {
                                Icon(Icons.Default.Mic, contentDescription = "Voice Input")
                            }

                            IconButton(onClick = {
                                imagePickerLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) {
                                Icon(Icons.Default.AttachFile, contentDescription = "Attach File")
                            }
                            
                            TextField(
                                value = inputText,
                                onValueChange = { inputText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("メッセージを入力...") },
                                maxLines = 4
                            )
                            
                            IconButton(
                                onClick = {
                                    if (inputText.isNotBlank() || selectedBitmap != null) {
                                        val text = inputText
                                        val bitmap = selectedBitmap
                                        inputText = ""
                                        selectedImageUri = null
                                        selectedBitmap = null
                                        viewModel.sendMessage(text, bitmap) { response ->
                                            if (isTtsEnabled) onSpeak(response)
                                        }
                                    }
                                },
                                enabled = !isLoading && (inputText.isNotBlank() || selectedBitmap != null)
                            ) {
                                Icon(Icons.Default.Send, contentDescription = "Send")
                            }
                        }
                    }
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(messages) { message ->
                        ChatBubble(message) { text ->
                            clipboardManager.setText(AnnotatedString(text))
                        }
                    }
                    if (isLoading) {
                        item {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .align(Alignment.CenterHorizontally)
                                    .padding(8.dp)
                                    .size(24.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(
            isTtsEnabled = isTtsEnabled,
            onTtsToggle = { viewModel.setTtsEnabled(it) },
            currentModel = currentModel,
            onModelChange = { viewModel.setModel(it) },
            geminiApiKey = customApiKey,
            onGeminiApiKeyChange = { viewModel.setApiKey(it) },
            openRouterApiKey = openRouterApiKey,
            onOpenRouterApiKeyChange = { viewModel.setOpenRouterApiKey(it) },
            onDismiss = { showSettings = false }
        )
    }
}

@Composable
fun SettingsDialog(
    isTtsEnabled: Boolean,
    onTtsToggle: (Boolean) -> Unit,
    currentModel: String,
    onModelChange: (String) -> Unit,
    geminiApiKey: String,
    onGeminiApiKeyChange: (String) -> Unit,
    openRouterApiKey: String,
    onOpenRouterApiKeyChange: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val geminiModels = listOf("gemini-2.5-flash", "gemini-2.5-flash-lite", "gemini-3-flash-preview")
    val openRouterModels = listOf(
        "google/gemma-4-31b-it:free",
        "google/gemma-2-9b-it:free",
        "meta-llama/llama-3.3-70b-instruct:free",
        "mistralai/mistral-small-3.1-24b:free",
        "qwen/qwen-2-7b-instruct:free",
        "deepseek/deepseek-r1:free"
    )
    
    var expanded by remember { mutableStateOf(false) }
    var tempGeminiApiKey by remember { mutableStateOf(geminiApiKey) }
    var tempOpenRouterApiKey by remember { mutableStateOf(openRouterApiKey) }
    var customModelName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("設定") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("音声読み上げ", modifier = Modifier.weight(1f))
                    Switch(checked = isTtsEnabled, onCheckedChange = onTtsToggle)
                }
                
                Column {
                    Text("モデル選択", style = MaterialTheme.typography.labelMedium)
                    Box {
                        OutlinedButton(
                            onClick = { expanded = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(currentModel)
                        }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            Text("Gemini", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            geminiModels.forEach { model ->
                                DropdownMenuItem(text = { Text(model) }, onClick = { onModelChange(model); expanded = false })
                            }
                            HorizontalDivider()
                            Text("OpenRouter (Free)", modifier = Modifier.padding(8.dp), style = MaterialTheme.typography.labelSmall, color = Color.Gray)
                            openRouterModels.forEach { model ->
                                DropdownMenuItem(text = { Text(model) }, onClick = { onModelChange(model); expanded = false })
                            }
                        }
                    }
                    
                    OutlinedTextField(
                        value = customModelName,
                        onValueChange = { customModelName = it },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        placeholder = { Text("カスタムモデル名を入力") },
                        trailingIcon = {
                            IconButton(onClick = { if (customModelName.isNotBlank()) onModelChange(customModelName) }) {
                                Icon(Icons.Default.Check, contentDescription = "Apply")
                            }
                        }
                    )
                }

                Column {
                    Text("Gemini API キー", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = tempGeminiApiKey,
                        onValueChange = { tempGeminiApiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = { onGeminiApiKeyChange(tempGeminiApiKey) }, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                        Text("保存")
                    }
                }

                Column {
                    Text("OpenRouter API キー", style = MaterialTheme.typography.labelMedium)
                    OutlinedTextField(
                        value = tempOpenRouterApiKey,
                        onValueChange = { tempOpenRouterApiKey = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Button(onClick = { onOpenRouterApiKeyChange(tempOpenRouterApiKey) }, modifier = Modifier.align(Alignment.End).padding(top = 4.dp)) {
                        Text("保存")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        }
    )
}

@Composable
fun ChatBubble(message: ChatMessage, onCopy: (String) -> Unit) {
    val alignment = if (message.isUser) Alignment.End else Alignment.Start
    val color = if (message.isUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
    
    Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        Surface(
            color = color,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (message.bitmap != null) {
                    Image(
                        bitmap = message.bitmap.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier
                            .sizeIn(maxWidth = 200.dp, maxHeight = 200.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                Text(
                    text = message.text,
                    style = MaterialTheme.typography.bodyLarge
                )
                
                IconButton(
                    onClick = { onCopy(message.text) },
                    modifier = Modifier.align(Alignment.End).size(24.dp)
                ) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
