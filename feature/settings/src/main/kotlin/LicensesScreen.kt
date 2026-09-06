package net.rokoucha.visiomata.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

data class LicenseUiModel(
    val name: String,
    val url: String?,
    val content: String?,
)

data class LibraryLicenseUiModel(
    val id: String,
    val name: String,
    val version: String?,
    val website: String?,
    val licenses: List<LicenseUiModel>,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(
    libraries: List<LibraryLicenseUiModel>,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    var selected by remember { mutableStateOf<LibraryLicenseUiModel?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(selected?.name ?: "オープンソースライセンス") },
                navigationIcon = {
                    if (selected != null || onBack != null) {
                        IconButton(onClick = { if (selected == null) onBack?.invoke() else selected = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "戻る")
                        }
                    }
                },
            )
        },
    ) { contentPadding ->
        val library = selected
        if (library == null) {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(contentPadding),
            ) {
                items(libraries, key = { it.id }) { item ->
                    ListItem(
                        supportingContent = {
                            Text(
                                listOfNotNull(
                                    item.version,
                                    item.licenses.joinToString { it.name }.takeIf(String::isNotBlank),
                                ).joinToString(" · "),
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null)
                        },
                        modifier = Modifier.fillMaxWidth().clickable { selected = item },
                    ) {
                        Text(item.name)
                    }
                }
            }
        } else {
            LicenseDetail(library, Modifier.padding(contentPadding))
        }
    }
}

@Composable
private fun LicenseDetail(
    library: LibraryLicenseUiModel,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
    ) {
        library.version?.let { Text("バージョン $it") }
        library.website?.let {
            Spacer(Modifier.height(8.dp))
            Text(it)
        }
        library.licenses.forEach { license ->
            Spacer(Modifier.height(24.dp))
            Text(license.name)
            license.url?.let {
                Spacer(Modifier.height(4.dp))
                Text(it)
            }
            Spacer(Modifier.height(12.dp))
            Text(
                license.content ?: "ライセンス本文は提供されていません。",
                fontFamily = FontFamily.Monospace,
            )
        }
        if (library.licenses.isEmpty()) {
            Spacer(Modifier.height(24.dp))
            Text("ライセンス情報は提供されていません。")
        }
    }
}
