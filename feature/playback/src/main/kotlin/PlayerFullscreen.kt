package net.rokoucha.visiomata.playback

import android.app.Activity
import android.content.pm.ActivityInfo

/**
 * ユーザー操作によるフルスクリーン再生中に要求する画面の向き。
 * センサー追従の横向きに固定し、回転ロック中でも横画面で再生できるようにする。
 */
internal val fullscreenLockedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE

/**
 * フルスクリーン再生の終了時・再生画面の破棄時に戻す画面の向き。
 * 固定を解除し、端末の設定やセンサーに追従させる。
 */
internal val fullscreenReleasedOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED

/**
 * 縦画面の再生UIからフルスクリーン再生へ遷移する。
 */
fun enterFullscreen(activity: Activity?) {
    activity?.requestedOrientation = fullscreenLockedOrientation
}

/**
 * フルスクリーン再生を終了し、画面の向きの固定を解除する。
 */
fun exitFullscreen(activity: Activity?) {
    activity?.requestedOrientation = fullscreenReleasedOrientation
}

/**
 * フルスクリーン遷移ボタンを表示するかどうか。
 * 縦画面のタッチデバイスでのみ表示し、TVやPiPウィンドウには出さない。
 */
internal fun shouldShowEnterFullscreen(
    isTv: Boolean,
    portrait: Boolean,
): Boolean = portrait && !isTv

/**
 * フルスクリーン終了ボタンを表示するかどうか。
 * ボタン操作でフルスクリーンへ遷移した横画面でのみ表示する。
 * 端末の回転による横画面では表示しない。
 */
internal fun shouldShowExitFullscreen(
    isTv: Boolean,
    portrait: Boolean,
    isFullscreen: Boolean,
): Boolean = isFullscreen && !portrait && !isTv
