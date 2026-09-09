package net.rokoucha.visiomata.playback

import android.app.Activity
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.trackPipAnimationHintView
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.core.util.Consumer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val pipAspectRatio = Rational(16, 9)

private val pipSwipeThreshold = 64.dp

fun supportsPictureInPicture(activity: Activity?): Boolean {
    if (activity == null) return false
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    return activity.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
}

/**
 * PiPへの遷移開始（[Activity.onPictureInPictureUiStateChanged]）で速やかにtrueへ遷移し、
 * 復帰（[Activity.onPictureInPictureModeChanged]）でfalseへ戻る。
 */
@Composable
fun rememberIsInPictureInPictureMode(activity: Activity?): Boolean {
    var isInPictureInPictureMode by remember(activity) {
        mutableStateOf(activity?.isInPictureInPictureMode == true)
    }
    val componentActivity = activity as? ComponentActivity
    DisposableEffect(componentActivity) {
        if (componentActivity == null) return@DisposableEffect onDispose {}
        val modeListener =
            Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                isInPictureInPictureMode = info.isInPictureInPictureMode
            }
        componentActivity.addOnPictureInPictureModeChangedListener(modeListener)
        val uiStateListener =
            Consumer<androidx.core.app.PictureInPictureUiStateCompat> { state ->
                if (state.isTransitioningToPip) isInPictureInPictureMode = true
            }
        componentActivity.addOnPictureInPictureUiStateChangedListener(uiStateListener)
        onDispose {
            componentActivity.removeOnPictureInPictureModeChangedListener(modeListener)
            componentActivity.removeOnPictureInPictureUiStateChangedListener(uiStateListener)
        }
    }
    return isInPictureInPictureMode
}

/**
 * 再生画面の表示中は自動進入を有効化し、画面を離れたら無効化する。
 * 遷移アニメーション用に [androidx.activity.trackPipAnimationHintView] で sourceRectHint を追従させる。
 */
@Composable
fun PictureInPictureAutoEnterEffect(
    activity: Activity?,
    enabled: Boolean,
    hintView: View?,
) {
    val scope = rememberCoroutineScope()
    DisposableEffect(activity, enabled, hintView) {
        if (activity == null || !enabled || !supportsPictureInPicture(activity)) {
            return@DisposableEffect onDispose {}
        }
        if (hintView != null) {
            scope.launch {
                try {
                    activity.trackPipAnimationHintView(hintView)
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (_: Exception) {
                    // アニメーション用ヒントの追従に失敗してもPiP自体は継続する。
                }
            }
        }
        activity.setPictureInPictureParams(
            PictureInPictureParams
                .Builder()
                .setAspectRatio(pipAspectRatio)
                .setAutoEnterEnabled(true)
                .build(),
        )
        onDispose {
            runCatching {
                activity.setPictureInPictureParams(
                    PictureInPictureParams
                        .Builder()
                        .setAutoEnterEnabled(false)
                        .build(),
                )
            }
        }
    }
}

/**
 * 映像面の下スワイプでPiPへ遷移するModifierを覚える。
 * [onEnterPictureInPicture] がnull（TV・PiP非対応）の場合は何もしない素のModifierを返す。
 * タップ（オーバーレイ切替）とは競合しない。ドラッグ量が閾値を超えた時点で1回だけ発火する。
 */
@Composable
fun rememberPictureInPictureSwipeModifier(onEnterPictureInPicture: (() -> Unit)?): Modifier {
    val currentEnterPictureInPicture by rememberUpdatedState(onEnterPictureInPicture)
    val density = LocalDensity.current
    val thresholdPx = remember(density) { with(density) { pipSwipeThreshold.toPx() } }
    return remember(thresholdPx) {
        if (onEnterPictureInPicture == null) {
            Modifier
        } else {
            Modifier.pointerInput(thresholdPx) {
                var distance = 0f
                detectVerticalDragGestures(
                    onDragStart = { distance = 0f },
                    onDragCancel = { distance = 0f },
                    onDragEnd = { distance = 0f },
                ) { _, dragAmount ->
                    distance += dragAmount
                    if (distance > thresholdPx) {
                        distance = 0f
                        currentEnterPictureInPicture?.invoke()
                    }
                }
            }
        }
    }
}

fun enterPictureInPicture(
    activity: Activity,
    view: View?,
): Boolean {
    if (!supportsPictureInPicture(activity)) return false
    val sourceRect = view?.let { Rect().also(it::getGlobalVisibleRect) }
    val params =
        PictureInPictureParams
            .Builder()
            .setAspectRatio(pipAspectRatio)
            .apply { sourceRect?.let(::setSourceRectHint) }
            .build()
    return activity.enterPictureInPictureMode(params)
}
