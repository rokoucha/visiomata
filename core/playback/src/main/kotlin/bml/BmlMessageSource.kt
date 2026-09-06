package net.rokoucha.visiomata.playback.bml

/**
 * Supplies the normalized messages consumed by web-bml.
 *
 * Implementations may obtain them from an MPEG-TS carousel or Mahiron's data-broadcast API.
 * Keeping the browser on this boundary prevents the acquisition strategy from leaking into the UI.
 */
internal interface BmlMessageSource {
    fun setConsumer(consumer: ((String) -> Unit)?)

    fun reset()

    fun release()
}
