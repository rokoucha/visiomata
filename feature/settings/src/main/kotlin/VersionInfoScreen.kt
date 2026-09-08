package net.rokoucha.visiomata.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp

data class AppVersionInfo(
    val appName: String,
    val versionName: String,
    val versionCode: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VersionInfoScreen(
    versionInfo: AppVersionInfo,
    onBack: (() -> Unit)?,
    onSendFeedback: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("バージョン情報") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        VersionInfoContent(
            versionInfo = versionInfo,
            onSendFeedback = onSendFeedback,
            modifier = Modifier.padding(contentPadding),
        )
    }
}

@Composable
fun VersionInfoContent(
    versionInfo: AppVersionInfo,
    onSendFeedback: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.size(24.dp))
        Image(
            painter = painterResource(R.drawable.visiomata_logo),
            contentDescription = null,
            modifier = Modifier.size(112.dp),
        )
        Spacer(Modifier.size(24.dp))
        Text(versionInfo.appName, style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.size(12.dp))
        Text(
            text = "バージョン ${versionInfo.versionName}",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.size(4.dp))
        Text(
            text = "ビルド ${versionInfo.versionCode}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (onSendFeedback != null) {
            Spacer(Modifier.size(24.dp))
            Button(onClick = onSendFeedback) {
                Text("フィードバックを送信")
            }
        }
    }
}
