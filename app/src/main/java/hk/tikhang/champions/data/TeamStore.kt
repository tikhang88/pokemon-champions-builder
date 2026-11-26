package hk.tikhang.champions.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import hk.tikhang.champions.core.SnapshotJson
import hk.tikhang.champions.core.Team
import kotlinx.coroutines.flow.Flow

/** Saved teams (spec §14.2 Room). The team itself is stored as JSON so the format can grow. */
@Entity(tableName = "teams")
data class TeamEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val json: String,
    val createdAt: Long,
    val updatedAt: Long,
) {
    fun team(): Team? = runCatching { SnapshotJson.decodeFromString(Team.serializer(), json) }.getOrNull()
}

@Dao
interface TeamDao {
    @Query("SELECT * FROM teams ORDER BY updatedAt DESC")
    fun all(): Flow<List<TeamEntity>>

    @Query("SELECT * FROM teams WHERE id = :id")
    suspend fun get(id: Long): TeamEntity?

    @Insert
    suspend fun insert(team: TeamEntity): Long

    @Update
    suspend fun update(team: TeamEntity)

    @Query("DELETE FROM teams WHERE id = :id")
    suspend fun delete(id: Long)
}

@Database(entities = [TeamEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun teams(): TeamDao

    companion object {
        @Volatile private var instance: AppDatabase? = null
        fun get(context: Context): AppDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "champions.db").build().also { instance = it }
        }
    }
}

fun Team.toJson(): String = SnapshotJson.encodeToString(Team.serializer(), this)
