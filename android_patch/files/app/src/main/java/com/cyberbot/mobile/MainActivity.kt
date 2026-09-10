package com.cyberbot.mobile

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.fragment.app.FragmentActivity
import com.cyberbot.mobile.ui.nav.CyberBotNavRoot
import com.cyberbot.mobile.ui.theme.CyberBotTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
// FragmentActivity (e nao ComponentActivity) porque o BiometricPrompt da tela de
// bloqueio exige um FragmentManager.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CyberBotTheme {
                CyberBotNavRoot()
            }
        }
    }
}
