package com.cyberbot.mobile.ui.common

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cyberbot.mobile.ui.theme.Border
import com.cyberbot.mobile.ui.theme.Surface

private val ALTURA_HEADER = 48.dp

/**
 * Cabeçalho compacto (48dp) em vez do TopAppBar do Material (64dp): sobra mais
 * altura para o conteúdo. Mesma API de antes (título + ações à direita).
 */
@Composable
fun ScreenScaffold(
    title: String,
    modifier: Modifier = Modifier,
    topBarActions: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .statusBarsPadding()
                    .height(ALTURA_HEADER)
                    .padding(start = 16.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                topBarActions()
            }
        },
    ) { innerPadding ->
        content(innerPadding)
    }
}

@Composable
fun CyberCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = Surface),
        border = BorderStroke(1.dp, Border),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            content = content,
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
fun LoadingPane(message: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(message)
        }
    }
}

@Composable
fun ErrorPane(message: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxWidth(),
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
fun SecureScreenEffect(enabled: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, enabled) {
        val activity = view.context as? Activity
        if (enabled) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
}
