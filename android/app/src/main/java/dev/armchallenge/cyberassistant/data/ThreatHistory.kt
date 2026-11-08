package dev.armchallenge.cyberassistant.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "scan_events")
data class ScanEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: String,
    val timestamp: Long,
    val resultLabel: String,
    val modelId: String,
    val modelVersion: String,
    val indicatorCodes: String,
    val status: String,
)

@Dao
interface ScanEventDao {
    @Insert suspend fun insert(event: ScanEvent)
    @Query("SELECT * FROM scan_events ORDER BY timestamp DESC LIMIT 100")
    fun observeRecent(): Flow<List<ScanEvent>>
    @Query("SELECT * FROM scan_events ORDER BY timestamp DESC LIMIT 1")
    suspend fun latest(): ScanEvent?
    @Query("DELETE FROM scan_events WHERE id = :id")
    suspend fun delete(id: Long)
    @Query("DELETE FROM scan_events")
    suspend fun deleteAll()
}

@Database(entities = [ScanEvent::class], version = 1, exportSchema = true)
abstract class ThreatDatabase : RoomDatabase() {
    abstract fun scanEventDao(): ScanEventDao
}
