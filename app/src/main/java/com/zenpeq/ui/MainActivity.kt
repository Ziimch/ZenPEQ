package com.zenpeq.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.zenpeq.audio.EqService

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { ZenTheme { ZenApp() } }
    }

    override fun onStart() {
        super.onStart()
        EqService.start(this) // foreground start is allowed while the activity is visible
    }
}
