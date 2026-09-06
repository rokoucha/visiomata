package net.rokoucha.visiomata.mirakurun

import net.rokoucha.visiomata.settings.data.AuthenticationType
import net.rokoucha.visiomata.settings.data.MirakurunSettings

class MirakurunConnectionUseCase {
    suspend operator fun invoke(settings: MirakurunSettings): ServerConnection =
        MirakurunConnector.connect(
            baseUrl = settings.url,
            username =
                settings.username.takeIf {
                    settings.authenticationType == AuthenticationType.Basic
                },
            password =
                settings.password.takeIf {
                    settings.authenticationType == AuthenticationType.Basic
                },
            bearerToken =
                settings.bearerToken.takeIf {
                    settings.authenticationType == AuthenticationType.Bearer
                },
        )

    suspend fun serverKind(settings: MirakurunSettings): ServerKind = invoke(settings).kind
}
