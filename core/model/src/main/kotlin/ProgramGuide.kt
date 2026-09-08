package net.rokoucha.visiomata.model

import java.text.Normalizer
import java.time.Instant

@JvmInline value class ChannelType(
    val value: String,
)

val ChannelType.displayOrder: Int
    get() =
        when (value) {
            "GR" -> 0
            "BS" -> 1
            "CS" -> 2
            "SKY" -> 3
            else -> 4
        }

data class ServiceKey(
    val networkId: Int,
    val transportStreamId: Int?,
    val serviceId: Int,
)

data class ProgramKey(
    val service: ServiceKey,
    val eventId: Int,
)

data class ProgramGenre(
    val level1: Int,
    val level2: Int,
)

data class ProgramAudio(
    val componentType: Int,
    val componentTag: Int,
    val isMain: Boolean,
    val languages: List<String> = emptyList(),
) {
    val isDualMono: Boolean get() = componentType and 0x1F == 0x02
}

data class RelatedProgram(
    val type: String,
    val networkId: Int,
    val transportStreamId: Int? = null,
    val serviceId: Int,
    val eventId: Int,
)

data class Service(
    val id: Long,
    val networkId: Int,
    val transportStreamId: Int?,
    val serviceId: Int,
    val name: String,
    val channelType: ChannelType,
    val channel: String,
    val remoteControlKeyId: Int?,
    val logoId: Int?,
) {
    val key: ServiceKey get() = ServiceKey(networkId, transportStreamId, serviceId)

    val remoteKeyNumber: Int? get() = remoteControlKeyId?.takeIf { it in 1..12 }

    /**
     * Viewer-facing three-digit channel number.
     *
     * ARIB TR-B14 9.1.3(d) defines the terrestrial number as
     * service type * 200 + remote key * 10 + (service number + 1), where both
     * service type and service number are encoded in service_id. Satellite
     * operational guidelines assign the service_id itself as the channel number.
     */
    val logicalChannelNumber: String
        get() =
            if (channelType.value == "GR" && remoteKeyNumber != null) {
                val serviceType = (serviceId shr 8) and 0b11
                val serviceNumber = serviceId and 0b111
                (serviceType * 200 + remoteKeyNumber!! * 10 + serviceNumber + 1)
                    .toString()
                    .padStart(3, '0')
            } else {
                serviceId.toString().padStart(3, '0')
            }

    val channelIdentityLabel: String
        get() = "${channelType.value}  $logicalChannelNumber"

    override fun equals(other: Any?): Boolean =
        other is Service &&
            id == other.id && networkId == other.networkId && serviceId == other.serviceId &&
            transportStreamId == other.transportStreamId && name == other.name &&
            channelType == other.channelType && channel == other.channel &&
            remoteControlKeyId == other.remoteControlKeyId && logoId == other.logoId

    override fun hashCode(): Int = 31 * id.hashCode() + (logoId ?: 0)
}

data class Program(
    val id: Long,
    val eventId: Int,
    val networkId: Int,
    val transportStreamId: Int?,
    val serviceId: Int,
    val title: String,
    val description: String,
    val startAt: Instant,
    val endAt: Instant,
    val primaryGenre: ProgramGenre? = null,
    val extended: Map<String, String> = emptyMap(),
    val relatedPrograms: List<RelatedProgram> = emptyList(),
    val audios: List<ProgramAudio> = emptyList(),
) {
    val key: ProgramKey get() = ProgramKey(ServiceKey(networkId, transportStreamId, serviceId), eventId)
}

data class ProgramGuideAvailability(
    val startAt: Instant,
    val endAt: Instant,
)

data class ServiceVariant(
    val id: Long,
    val logicalChannelNumber: String,
    val name: String,
    val logoLabel: String = name.take(6),
    val logoId: Int? = null,
)

data class ServiceGroup(
    val id: String,
    val primaryServiceId: Long,
    val channelType: ChannelType,
    val logicalChannelNumber: String,
    val serviceName: String,
    val logoLabel: String,
    val logoId: Int?,
    val current: Program?,
    val next: Program?,
    val variants: List<ServiceVariant>,
) {
    val channelIdentityLabel: String
        get() = "${channelType.value}  $logicalChannelNumber"
}

data class ProgramGuide(
    val services: List<Service>,
    val programs: List<Program>,
) {
    private val programsByService by lazy {
        programs
            .groupBy { ServiceKey(it.networkId, it.transportStreamId, it.serviceId) }
            .mapValues { (_, value) -> value.sortedBy { it.startAt } }
    }

    fun schedule(service: Service): List<Program> = programsByService[service.key].orEmpty()

    /**
     * Schedule shown in a station-level guide. A primary service keeps its full
     * schedule; a subchannel keeps only programmes that differ from the primary.
     */
    fun displaySchedule(service: Service): List<Program> {
        val group = services.filter { it.stationGroupKey() == service.stationGroupKey() }
        val primary = group.primaryService()
        val serviceSchedule = schedule(service)
        if (service == primary) return serviceSchedule
        val primarySchedule = schedule(primary)
        return serviceSchedule.filter { sub -> primarySchedule.none { it.isSameBroadcastAs(sub) } }
    }

    /**
     * Services suitable for station-level lists such as an EPG.
     *
     * ARIB TR-B14 defines the terrestrial service number as the lower three bits of
     * service_id and service number 0 as the broadcaster's primary service. For other
     * media, only services carrying the same station identity (remote key or logo) in
     * the same transport stream are collapsed, so unrelated services sharing a
     * transponder remain visible.
     */
    val primaryServices: List<Service>
        get() =
            services.groupBy { it.stationGroupKey() }.values.map { group ->
                group.minWithOrNull(compareBy<Service>({ it.aribServiceNumber }, { it.serviceId }))!!
            }

    val servicesWithDistinctProgramming: List<Service>
        get() =
            services.groupBy { it.stationGroupKey() }.values.flatMap { group ->
                val primary = group.primaryService()
                listOf(primary) +
                    group
                        .asSequence()
                        .filter { it != primary }
                        .filter { displaySchedule(it).isNotEmpty() }
                        .sortedBy { it.serviceId }
                        .toList()
            }

    /**
     * Builds a stable render snapshot while a neighbouring guide window is being loaded.
     * Programmes from the previous snapshot fill transient Room emissions, then anything
     * outside the newly requested window is discarded.
     */
    fun retainingWindow(
        previous: ProgramGuide,
        startAt: Instant,
        endAt: Instant,
    ): ProgramGuide {
        val retainedServices = services.ifEmpty { previous.services }
        val serviceKeys = retainedServices.mapTo(hashSetOf()) { it.key }
        val retainedPrograms =
            (programs + previous.programs)
                .asSequence()
                .filter { it.key.service in serviceKeys }
                .filter { it.startAt < endAt && it.endAt > startAt }
                .distinctBy { it.id }
                .sortedBy { it.startAt }
                .toList()
        return ProgramGuide(retainedServices, retainedPrograms)
    }

    fun serviceGroups(at: Instant = Instant.now()): List<ServiceGroup> {
        val entries =
            services.map { service ->
                val schedule = schedule(service)
                val current =
                    schedule.lastOrNull { !at.isBefore(it.startAt) && at.isBefore(it.endAt) }
                Entry(service, current, schedule.firstOrNull { !it.startAt.isBefore(current?.endAt ?: at) })
            }
        val visibleEntries =
            entries.groupBy { it.service.stationGroupKey() }.values.flatMap { group ->
                val primary =
                    group.minWithOrNull(
                        compareBy<Entry>({ it.service.aribServiceNumber }, { it.service.serviceId }),
                    )!!
                listOf(primary) +
                    group
                        .asSequence()
                        .filter { it != primary }
                        // Only hide a subchannel when both programmes confirm a simulcast.
                        .filter { entry ->
                            val current = entry.current
                            val primaryCurrent = primary.current
                            current == null || primaryCurrent == null || !current.isSameBroadcastAs(primaryCurrent)
                        }.sortedBy { it.service.serviceId }
                        .toList()
            }
        return visibleEntries.map { listOf(it).toServiceGroup() }.sortedWith(
            compareBy(
                { it.channelType.displayOrder },
                { it.channelType.value },
                { it.logicalChannelNumber.toIntOrNull() ?: Int.MAX_VALUE },
                { it.logicalChannelNumber },
                { it.serviceName },
            ),
        )
    }

    private fun List<Entry>.toServiceGroup(): ServiceGroup {
        val primary = minBy { it.service.serviceId }
        val service = primary.service
        return ServiceGroup(
            id = map { it.service.id }.sorted().joinToString("-"),
            primaryServiceId = service.id,
            channelType = service.channelType,
            logicalChannelNumber = service.logicalChannelNumber,
            serviceName = service.name,
            logoLabel = service.name.take(6),
            logoId = service.logoId,
            current = primary.current,
            next = primary.next,
            variants =
                if (size > 1) {
                    filter { it.service.id != service.id }.sortedBy { it.service.serviceId }.map {
                        ServiceVariant(
                            id = it.service.id,
                            logicalChannelNumber = it.service.logicalChannelNumber,
                            name = it.service.name,
                            logoLabel = it.service.name.take(6),
                            logoId = it.service.logoId,
                        )
                    }
                } else {
                    emptyList()
                },
        )
    }
}

private data class Entry(
    val service: Service,
    val current: Program?,
    val next: Program?,
)

private val Service.aribServiceNumber: Int
    get() = if (channelType.value == "GR") serviceId and 0b111 else serviceId

private fun List<Service>.primaryService(): Service =
    minWithOrNull(compareBy<Service>({ it.aribServiceNumber }, { it.serviceId }))!!

private fun Program.isSameBroadcastAs(other: Program): Boolean {
    val ownGroup = eventCommonGroup()
    val otherGroup = other.eventCommonGroup()
    if (ownGroup.size > 1 || otherGroup.size > 1) return ownGroup.any { it in otherGroup }

    // Some Mirakurun-compatible servers omit the Event Group Descriptor. EIT timings
    // can differ slightly between services, so exact start/end equality is not valid.
    return title.normalizedForComparison() == other.title.normalizedForComparison() &&
        startAt < other.endAt && other.startAt < endAt
}

private fun Program.eventCommonGroup(): Set<ProgramKey> =
    buildSet {
        add(key)
        relatedPrograms.asSequence().filter { it.type == "shared" }.forEach { related ->
            add(
                ProgramKey(
                    ServiceKey(related.networkId, related.transportStreamId ?: transportStreamId, related.serviceId),
                    related.eventId,
                ),
            )
        }
    }

private fun String.normalizedForComparison(): String = replace(Regex("[\\s　]+"), " ").trim()

private fun Service.stationGroupKey(): List<Any?> =
    listOf(
        networkId,
        transportStreamId,
        when (channelType.value) {
            "BS" -> bsStationName()
            else -> remoteControlKeyId?.takeIf { it > 0 } ?: logoId ?: serviceId
        },
    )

/**
 * BS assigns the three-digit service_id directly as the channel number (TR-B15).
 * Broadcasters commonly suffix member-service names with that number, or 1/2/3,
 * while logo_id itself may differ per member service.
 */
private fun Service.bsStationName(): String {
    val normalized =
        Normalizer
            .normalize(name, Normalizer.Form.NFKC)
            .replace(Regex("[\\s　]+"), "")
    val withoutServiceId =
        normalized
            .removeSuffix(serviceId.toString())
            .trimEnd('・', '-', '_')
    if (withoutServiceId != normalized) return withoutServiceId
    return normalized.dropLastWhile { it in '1'..'3' }.trimEnd('・', '-', '_')
}
