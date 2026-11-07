package app.englishdub.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "jobs")
data class DubJob(
    @PrimaryKey val id: String,
    val title: String,
    val mediaUri: String,
    val subtitleUri: String? = null,
    val sourceLanguage: String = "en",
    val mode: String = "subtitles",
    val state: String = "new",
    val progress: Int = 0,
    val message: String = "Choose a dubbing option or watch the original.",
    val audioPath: String? = null,
    val durationMs: Long = 0,
    val decodedUntilMs: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "NULL") val preparationSignature: String? = null,
    @ColumnInfo(defaultValue = "NULL") val voiceSignature: String? = null,
)

@Entity(tableName = "cues", primaryKeys = ["jobId", "index"],
    foreignKeys = [ForeignKey(entity = DubJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)])
data class SavedCue(
    val jobId: String,
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val text: String,
    val english: String? = null,
    val pcmPath: String? = null,
    val rate: Int = 0,
    val spokenStartMs: Long = 0,
    val spokenEndMs: Long = 0,
)

@Dao
interface JobsDao {
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC") fun observe(): Flow<List<DubJob>>
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: String): DubJob?
    @Upsert suspend fun save(job: DubJob)
    @Query("UPDATE jobs SET progress = :progress, message = :message WHERE id = :id")
    suspend fun progress(id: String, progress: Int, message: String)
    @Query("UPDATE jobs SET state = :state, message = :message WHERE id = :id")
    suspend fun state(id: String, state: String, message: String)
    @Query("UPDATE jobs SET decodedUntilMs = :until WHERE id = :id")
    suspend fun decoded(id: String, until: Long)
    @Query("UPDATE jobs SET mode = :mode, sourceLanguage = :language, state = 'new', progress = 0, message = 'Ready to prepare.' WHERE id = :id")
    suspend fun request(id: String, mode: String, language: String)
    @Query("UPDATE cues SET pcmPath = NULL, rate = 0, spokenStartMs = 0, spokenEndMs = 0 WHERE jobId = :id")
    suspend fun invalidateVoice(id: String)
    @Query("DELETE FROM jobs WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM cues WHERE jobId = :id ORDER BY `index`") suspend fun cues(id: String): List<SavedCue>
    @Upsert suspend fun saveCue(cue: SavedCue)
    @Upsert suspend fun saveCues(cues: List<SavedCue>)
    @Query("DELETE FROM cues WHERE jobId = :id") suspend fun clearCues(id: String)
    @Query("UPDATE jobs SET state = 'paused', message = 'Preparation interrupted. Tap Resume to continue.' WHERE state IN ('preparing', 'downloading')")
    suspend fun recoverInterrupted()
}

@Database(entities = [DubJob::class, SavedCue::class], version = 2, exportSchema = false)
abstract class DubDatabase : RoomDatabase() {
    abstract fun jobs(): JobsDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN preparationSignature TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN voiceSignature TEXT DEFAULT NULL")
            }
        }
    }
}
