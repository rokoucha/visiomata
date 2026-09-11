package net.rokoucha.visiomata.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.rokoucha.visiomata.playback.mpeg2toh264.Mpeg2DecoderCapabilities

/**
 * Whether the device exposes any MPEG-2 decoder. Null while the blocking codec enumeration
 * runs off the main thread. Callers must treat null as unknown and keep every option enabled.
 */
@Composable
internal fun rememberMpeg2DecoderPresent(): Boolean? {
    var present by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        present = withContext(Dispatchers.IO) { Mpeg2DecoderCapabilities.hasAnyDecoder }
    }
    return present
}
