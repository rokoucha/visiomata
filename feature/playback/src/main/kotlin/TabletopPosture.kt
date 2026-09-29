package net.rokoucha.visiomata.playback

import androidx.compose.material3.adaptive.currentWindowAdaptiveInfoV2
import androidx.compose.material3.adaptive.separatingHorizontalHingeBounds
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * tabletop posture（半開き・ヒンジ水平）における映像領域と折り目の寸法。
 * 映像はウィンドウ上端から折り目の上端までの領域に収め、下半分に操作UIを配置する。
 */
internal data class TabletopSplit(
    val videoHeight: Dp,
    val hingeHeight: Dp,
)

/**
 * 現在のウィンドウ姿勢からtabletop用の分割位置を解決する。
 * tabletop postureでない場合や、水平方向の分離ヒンジが報告されない場合はnull。
 */
@Composable
internal fun rememberTabletopSplit(): TabletopSplit? {
    val posture = currentWindowAdaptiveInfoV2().windowPosture
    val density = LocalDensity.current
    return remember(posture, density) {
        if (!posture.isTabletop) return@remember null
        val hinge = posture.separatingHorizontalHingeBounds.firstOrNull() ?: return@remember null
        with(density) {
            TabletopSplit(
                videoHeight = hinge.top.toDp().coerceAtLeast(0.dp),
                hingeHeight = hinge.height.toDp().coerceAtLeast(0.dp),
            )
        }
    }
}

/**
 * tabletopレイアウトを使うかどうか。
 * TVでは折りたたみ姿勢を取らないため、分割位置があっても通常レイアウトを使う。
 */
internal fun shouldUseTabletopLayout(
    isTv: Boolean,
    tabletopSplit: TabletopSplit?,
): Boolean = !isTv && tabletopSplit != null
