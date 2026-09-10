package com.cyberbot.mobile.ui.placeholder

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.common.CyberCard
import com.cyberbot.mobile.ui.common.ScreenScaffold

@Composable
fun FeaturePlaceholderRoute(
    title: String,
    message: String,
) {
    ScreenScaffold(title = title) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            CyberCard(modifier = Modifier.fillMaxWidth()) {
                Text(message)
            }
        }
    }
}
