package net.rokoucha.visiomata

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import net.rokoucha.visiomata.data.ProgramGuideUseCases
import net.rokoucha.visiomata.mirakurun.MirakurunConnectionUseCase
import net.rokoucha.visiomata.mirakurun.PlaybackSessionUseCase
import net.rokoucha.visiomata.settings.data.MirakurunSettingsUseCases
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DependencyModule {
    @Provides
    @Singleton
    fun settingsUseCases(
        @ApplicationContext context: Context,
    ) = MirakurunSettingsUseCases(context)

    @Provides
    @Singleton
    fun programGuideUseCases(
        @ApplicationContext context: Context,
    ) = ProgramGuideUseCases(context)

    @Provides
    @Singleton
    fun connectionUseCase() = MirakurunConnectionUseCase()

    @Provides
    @Singleton
    fun playbackSessionUseCase(connectionUseCase: MirakurunConnectionUseCase) =
        PlaybackSessionUseCase(connectionUseCase)
}
