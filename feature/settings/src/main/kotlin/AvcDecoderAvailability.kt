package net.rokoucha.visiomata.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.playback.mpeg2toh264.AvcDecoderCapabilities

/**
 * Whether the device exposes a software H.264 decoder. Null while the blocking codec
 * enumeration runs off the main thread. Callers must treat null as unknown and keep every
 * option enabled.
 */
@Composable
internal fun rememberAvcSoftwareDecoderPresent(): Boolean? {
    var present by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        present = withContext(Dispatchers.IO) { AvcDecoderCapabilities.hasSoftwareDecoder }
    }
    return present
}
