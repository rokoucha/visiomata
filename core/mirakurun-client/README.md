# Mirakurun client

Android/Kotlin clients generated independently from the Mirakurun and Mahiron
OpenAPI definitions. `server.common` is typed only from Mirakurun's schema, so
Mahiron-only operations cannot accidentally be called through it. The Mahiron
client is exposed only after capability negotiation.

```kotlin
val server = MirakurunConnector.connect(
    baseUrl = "https://example.test",
    username = "user",
    password = "password",
)

val services = server.common.services.getServices()

// Non-null only when /api/version contains { "server": "mahiron" }.
server.mahiron?.services?.getServiceDataBroadcastState(services.first().id)
```

The connector intentionally checks the explicit `server` discriminator instead
of inferring the implementation from version numbers. This keeps development
builds and future Mirakurun versions unambiguous.

Update either schema under `src/main/openapi`, then run:

```shell
./gradlew :core:mirakurun-client:generateMirakurunApi \
  :core:mirakurun-client:generateMahironApi
```

Normal Kotlin compilation depends on these tasks, then arranges Kotlin files as
`build/generated/kotlin/<server>/{api,model,infrastructure}` without the full
package-directory nesting. Generated files are not checked in.
