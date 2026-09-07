package net.rokoucha.visiomata

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

@Composable
internal fun LocalNetworkPermissionScreen(
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isTv =
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK == Configuration.UI_MODE_TYPE_TELEVISION
    val focusRequester = remember { FocusRequester() }
    AppTheme(isTv = isTv, modifier = modifier) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .safeDrawingPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("ローカルネットワークへのアクセス")
            Text("LAN 内のテレビサーバーに接続するため、「付近のデバイス」の権限を許可してください。")
            Button(onClick = onRequestPermission, modifier = Modifier.focusRequester(focusRequester)) {
                Text("アクセスを許可")
            }
            Text("許可画面が表示されない場合は、アプリの設定から権限を許可してください。")
            Button(onClick = onOpenSettings) {
                Text("アプリの設定を開く")
            }
        }
    }
    LaunchedEffect(isTv) {
        if (isTv) focusRequester.requestFocus()
    }
}
