package io.github.daepilot.data.db

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "subscriptions")
data class SubscriptionEntity(
    @PrimaryKey val id: String,
    val name: String,
    val url: String,
    val enabled: Boolean,
    val autoUpdate: Boolean,
    val updateIntervalHours: Int,
    val lastUpdatedAt: Long,
    val lastError: String?,
    val uploadBytes: Long,
    val downloadBytes: Long,
    val totalBytes: Long,
    val expireAt: Long,
    val nodeCount: Int,
    val providerIntervalHours: Int?,
    val etag: String?,
)

@Entity(
    tableName = "nodes",
    indices = [Index("subscriptionId"), Index("latencyMs"), Index("lastTestedAt")],
)
data class NodeEntity(
    @PrimaryKey val id: String,
    val name: String,
    val protocol: String,
    val link: String,
    val subscriptionId: String?,
    val latencyMs: Int?,
    val lastTestedAt: Long,
    val favorite: Boolean,
    val createdAt: Long,
    val countryCode: String?,
)

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<SubscriptionEntity>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getById(id: String): SubscriptionEntity?

    @Upsert
    suspend fun upsert(entity: SubscriptionEntity)

    @Query("UPDATE subscriptions SET lastUpdatedAt = :at, lastError = :error, nodeCount = :count, etag = :etag WHERE id = :id")
    suspend fun updateResult(id: String, at: Long, error: String?, count: Int, etag: String?)

    @Query("DELETE FROM subscriptions WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface NodeDao {
    @Query("SELECT * FROM nodes ORDER BY name COLLATE NOCASE")
    fun observeAll(): Flow<List<NodeEntity>>

    @Query("SELECT * FROM nodes ORDER BY name COLLATE NOCASE")
    suspend fun getAll(): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE subscriptionId = :subscriptionId ORDER BY name COLLATE NOCASE")
    suspend fun getBySubscription(subscriptionId: String): List<NodeEntity>

    @Query("SELECT * FROM nodes WHERE id = :id")
    suspend fun getById(id: String): NodeEntity?

    @Query("SELECT COUNT(*) FROM nodes WHERE subscriptionId = :subscriptionId")
    suspend fun countBySubscription(subscriptionId: String): Int

    @Query("SELECT EXISTS(SELECT 1 FROM nodes WHERE id = :id)")
    suspend fun exists(id: String): Boolean

    @Upsert
    suspend fun upsertAll(entities: List<NodeEntity>)

    @Query("DELETE FROM nodes WHERE subscriptionId = :subscriptionId")
    suspend fun deleteBySubscription(subscriptionId: String)

    @Query("DELETE FROM nodes WHERE id = :id")
    suspend fun delete(id: String)

    @Query("UPDATE nodes SET latencyMs = :latency, lastTestedAt = :at WHERE id = :id")
    suspend fun updateLatency(id: String, latency: Int?, at: Long)

    @Query("UPDATE nodes SET favorite = :favorite WHERE id = :id")
    suspend fun setFavorite(id: String, favorite: Boolean)

    @Query("UPDATE nodes SET latencyMs = NULL, lastTestedAt = 0")
    suspend fun clearLatency()
}

@Database(
    entities = [SubscriptionEntity::class, NodeEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun subscriptionDao(): SubscriptionDao
    abstract fun nodeDao(): NodeDao

    companion object {
        @Volatile
        private var instance: AppDatabase? = null

        fun get(context: android.content.Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: androidx.room.Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "daepilot.db",
                ).fallbackToDestructiveMigration().build().also { instance = it }
            }
    }
}
