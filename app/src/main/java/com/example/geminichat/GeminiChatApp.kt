package com.example.geminichat

import android.app.Application

class GeminiChatApp : Application() {
    override fun onCreate() {
        super.onCreate()
        ObjectBox.init(this)
    }
}
