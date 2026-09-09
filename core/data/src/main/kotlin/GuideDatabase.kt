package net.rokoucha.visiomata.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(
    tableName = "services",
    indices = [
        Index(value = ["source", "id"]),
        Index(value = ["source", "channelType", "networkId", "serviceId"]),
    ],
)
internal data class ServiceEntity(
    @PrimaryKey val cacheId: String,
    val source: String,
    val id: Long,
    val networkId: Int,
    val transportStreamId: Int?,
    val serviceId: Int,
    val name: String,
    val channelType: String,
    val channel: String,
    val remoteControlKeyId: Int?,
    val logoId: Int?,
)

@Entity(
    tableName = "programs",
    indices = [Index(value = ["source", "networkId", "serviceId", "startAt", "id"])],
)
internal data class ProgramEntity(
    @PrimaryKey val cacheId: String,
    val source: String,
    val id: Long,
    val eventId: Int,
    val networkId: Int,
    val transportStreamId: Int?,
    val serviceId: Int,
    val title: String?,
    val description: String?,
    val startAt: Long,
    val duration: Long,
    val genreLevel1: Int?,
    val genreLevel2: Int?,
    val extendedJson: String?,
    val relatedItemsJson: String? = null,
    val audiosJson: String? = null,
)

@Entity(tableName = "guide_cache")
internal data class GuideCacheEntity(
    @PrimaryKey val source: String,
    val refreshedAt: Long,
    val lastEventAt: Long? = null,
)

internal data class ServiceProgramUpdate(
    val networkId: Int,
    val serviceId: Int,
    val programs: List<ProgramEntity>,
)

@Dao
internal interface GuideDao {
    @Query("SELECT * FROM services WHERE source = :source ORDER BY id")
    @Transaction
    fun observeServices(source: String): Flow<List<ServiceEntity>>

    @Query("SELECT * FROM programs WHERE source = :source ORDER BY startAt")
    @Transaction
    fun observePrograms(source: String): Flow<List<ProgramEntity>>

    @Query(
        "SELECT * FROM services WHERE source = :source AND channelType = :channelType ORDER BY remoteControlKeyId, serviceId",
    )
    fun observeGuideServices(
        source: String,
        channelType: String,
    ): Flow<List<ServiceEntity>>

    @Query(
        """
        SELECT programs.* FROM programs
        INNER JOIN services ON programs.source = services.source
            AND programs.networkId = services.networkId
            AND programs.serviceId = services.serviceId
        WHERE programs.source = :source
            AND services.channelType = :channelType
            AND programs.startAt < :endAt
            AND programs.startAt + programs.duration > :startAt
        ORDER BY programs.startAt, programs.id
    """,
    )
    fun observeGuidePrograms(
        source: String,
        channelType: String,
        startAt: Long,
        endAt: Long,
    ): Flow<List<ProgramEntity>>

    @Query(
        """
        SELECT cacheId, source, id, eventId, networkId, transportStreamId, serviceId,
            title, description, startAt, duration, genreLevel1, genreLevel2, extendedJson,
            relatedItemsJson, audiosJson
        FROM (
            SELECT programs.*,
                ROW_NUMBER() OVER (
                    PARTITION BY source, networkId, serviceId
                    ORDER BY startAt, id
                ) AS homeRank
            FROM programs
            WHERE source = :source AND startAt + duration > :now
        )
        WHERE homeRank <= 2
        ORDER BY startAt
    """,
    )
    @Transaction
    fun observeHomePrograms(
        source: String,
        now: Long,
    ): Flow<List<ProgramEntity>>

    @Query("SELECT * FROM services WHERE source = :source AND channelType = :channelType ORDER BY id")
    suspend fun services(
        source: String,
        channelType: String,
    ): List<ServiceEntity>

    @Query("SELECT DISTINCT channelType FROM services WHERE source = :source")
    suspend fun serviceChannelTypes(source: String): List<String>

    @Query(
        "SELECT COUNT(*) FROM programs INNER JOIN services ON programs.source = services.source AND programs.networkId = services.networkId AND programs.serviceId = services.serviceId WHERE programs.source = :source AND services.channelType = :channelType AND programs.startAt + programs.duration > :now",
    )
    suspend fun futureProgramCount(
        source: String,
        channelType: String,
        now: Long,
    ): Int

    @Query("SELECT * FROM services WHERE cacheId = :cacheId")
    suspend fun service(cacheId: String): ServiceEntity?

    @Query("SELECT * FROM programs WHERE cacheId = :cacheId")
    suspend fun program(cacheId: String): ProgramEntity?

    @Query("SELECT * FROM guide_cache WHERE source = :source")
    suspend fun cache(source: String): GuideCacheEntity?

    @Query("UPDATE guide_cache SET lastEventAt = :lastEventAt WHERE source = :source")
    suspend fun updateLastEventAt(
        source: String,
        lastEventAt: Long,
    )

    @Query(
        "SELECT transportStreamId FROM services WHERE source = :source AND networkId = :networkId AND serviceId = :serviceId LIMIT 1",
    )
    suspend fun transportStreamId(
        source: String,
        networkId: Int,
        serviceId: Int,
    ): Int?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertServices(services: List<ServiceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPrograms(programs: List<ProgramEntity>)

    @Upsert
    suspend fun upsertCache(cache: GuideCacheEntity)

    @Query("UPDATE programs SET extendedJson = :extendedJson WHERE cacheId = :cacheId")
    suspend fun updateProgramExtended(
        cacheId: String,
        extendedJson: String,
    )

    @Query("UPDATE programs SET audiosJson = :audiosJson WHERE cacheId = :cacheId")
    suspend fun updateProgramAudios(
        cacheId: String,
        audiosJson: String,
    )

    @Query("DELETE FROM services WHERE cacheId = :cacheId")
    suspend fun deleteService(cacheId: String)

    @Query("DELETE FROM programs WHERE cacheId = :cacheId")
    suspend fun deleteProgram(cacheId: String)

    @Query("DELETE FROM programs WHERE source = :source AND networkId = :networkId AND serviceId = :serviceId")
    suspend fun deleteProgramsForService(
        source: String,
        networkId: Int,
        serviceId: Int,
    )

    @Transaction
    suspend fun replaceProgramsForService(
        source: String,
        networkId: Int,
        serviceId: Int,
        programs: List<ProgramEntity>,
    ) {
        deleteProgramsForService(source, networkId, serviceId)
        insertPrograms(programs)
    }

    @Transaction
    suspend fun replaceProgramsForServices(
        source: String,
        updates: List<ServiceProgramUpdate>,
    ) {
        updates.forEach { update ->
            deleteProgramsForService(source, update.networkId, update.serviceId)
            insertPrograms(update.programs)
        }
    }

    @Query(
        "UPDATE programs SET transportStreamId = :transportStreamId WHERE source = :source AND networkId = :networkId AND serviceId = :serviceId",
    )
    suspend fun updateProgramTransportStream(
        source: String,
        networkId: Int,
        serviceId: Int,
        transportStreamId: Int?,
    )

    @Query("DELETE FROM services WHERE source = :source")
    suspend fun deleteServices(source: String)

    @Query("DELETE FROM programs WHERE source = :source")
    suspend fun deletePrograms(source: String)

    @Transaction
    suspend fun replaceServices(
        source: String,
        services: List<ServiceEntity>,
        refreshedAt: Long,
    ) {
        deleteServices(source)
        insertServices(services)
        upsertCache(GuideCacheEntity(source, refreshedAt, cache(source)?.lastEventAt))
    }

    /**
     * Replaces the service catalogue and every service's home programmes in one
     * transaction so observers never see a partially refreshed home snapshot.
     */
    @Transaction
    suspend fun replaceHomeSnapshot(
        source: String,
        services: List<ServiceEntity>,
        updates: List<ServiceProgramUpdate>,
        refreshedAt: Long,
    ) {
        deleteServices(source)
        insertServices(services)
        upsertCache(GuideCacheEntity(source, refreshedAt, cache(source)?.lastEventAt))
        updates.forEach { update ->
            deleteProgramsForService(source, update.networkId, update.serviceId)
            insertPrograms(update.programs)
        }
    }

    @Query("UPDATE services SET source = :target, cacheId = :target || ':' || id WHERE source = :staging")
    suspend fun promoteServices(
        staging: String,
        target: String,
    )

    @Query("UPDATE programs SET source = :target, cacheId = :target || ':' || id WHERE source = :staging")
    suspend fun promotePrograms(
        staging: String,
        target: String,
    )

    @Transaction
    suspend fun promote(
        staging: String,
        source: String,
        refreshedAt: Long,
    ) {
        deleteServices(source)
        deletePrograms(source)
        promoteServices(staging, source)
        promotePrograms(staging, source)
        upsertCache(GuideCacheEntity(source, refreshedAt, cache(source)?.lastEventAt))
    }
}

@Database(
    entities = [ServiceEntity::class, ProgramEntity::class, GuideCacheEntity::class],
    version = 1,
    exportSchema = true,
)
internal abstract class GuideDatabase : RoomDatabase() {
    abstract fun guideDao(): GuideDao

    companion object {
        @Volatile private var instance: GuideDatabase? = null

        fun get(context: Context): GuideDatabase =
            instance ?: synchronized(this) {
                instance ?: Room
                    .databaseBuilder(
                        context.applicationContext,
                        GuideDatabase::class.java,
                        "visiomata.db",
                    ).build()
                    .also { instance = it }
            }
    }
}
