package net.rokoucha.visiomata.settings.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow

class MirakurunSettingsUseCases(
    context: Context,
) {
    private val repository = MirakurunSettingsRepository(context)

    val settings: StateFlow<MirakurunSettings> = repository.settings

    fun update(settings: MirakurunSettings) = repository.update(settings)
}
