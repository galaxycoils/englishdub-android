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
    @ColumnInfo(defaultValue = "-1") val audioTrackIndex: Int = -1,
    @ColumnInfo(defaultValue = "-1") val subtitleTrackIndex: Int = -1,
    @ColumnInfo(defaultValue = "0") val subtitleOffsetMs: Long = 0,
    @ColumnInfo(defaultValue = "0") val overrideRecommendation: Boolean = false,
    @ColumnInfo(defaultValue = "NULL") val voiceName: String? = null,
    @ColumnInfo(defaultValue = "NULL") val recognitionSignature: String? = null,
    @ColumnInfo(defaultValue = "NULL") val translationSignature: String? = null,
    @ColumnInfo(defaultValue = "NULL") val timelineSignature: String? = null,
    @ColumnInfo(defaultValue = "NULL") val previewAudioPath: String? = null,
    @ColumnInfo(defaultValue = "0") val previewStartMs: Long = 0,
    @ColumnInfo(defaultValue = "'none'") val previewState: String = "none",
    @ColumnInfo(defaultValue = "''") val previewMessage: String = "",
    @ColumnInfo(defaultValue = "0") val queueOrder: Int = 0,
    @ColumnInfo(defaultValue = "1") val chargingOnly: Boolean = true,
    @ColumnInfo(defaultValue = "0.15") val backgroundVolume: Float = 0.15f,
    @ColumnInfo(defaultValue = "1") val captionsEnabled: Boolean = true,
    @ColumnInfo(defaultValue = "16.0") val captionSizeSp: Float = 16f,
    @ColumnInfo(defaultValue = "0") val playbackPositionMs: Long = 0,
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
    @ColumnInfo(defaultValue = "NULL") val editedEnglish: String? = null,
    @ColumnInfo(defaultValue = "NULL") val startOverrideMs: Long? = null,
    @ColumnInfo(defaultValue = "NULL") val endOverrideMs: Long? = null,
    @ColumnInfo(defaultValue = "0") val subtitlesOnly: Boolean = false,
    @ColumnInfo(defaultValue = "1") val transcriptAvailable: Boolean = true,
) {
    fun effectiveStartMs(subtitleOffsetMs: Long = 0L): Long =
        (startOverrideMs ?: (startMs + subtitleOffsetMs)).coerceAtLeast(0L)

    fun effectiveEndMs(subtitleOffsetMs: Long = 0L): Long =
        maxOf(effectiveStartMs(subtitleOffsetMs) + 1L, endOverrideMs ?: (endMs + subtitleOffsetMs))

    fun effectiveEnglish(): String = (editedEnglish ?: english ?: text).trim()

    /** Speech translation outputs English directly; never present it as an original-language transcript. */
    fun originalTranscript(mode: String): String? =
        if (mode == "speech" || !transcriptAvailable) null else text.takeIf { it.isNotBlank() }

    fun hasTimingWarning(subtitleOffsetMs: Long = 0L): Boolean =
        !subtitlesOnly && spokenEndMs > 0 &&
            (spokenEndMs > effectiveEndMs(subtitleOffsetMs) + 150 || spokenStartMs > effectiveStartMs(subtitleOffsetMs) + 150)
}

@Entity(tableName = "pronunciations", primaryKeys = ["jobId", "original"],
    foreignKeys = [ForeignKey(entity = DubJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)])
data class PronunciationEntry(
    val jobId: String,
    val original: String,
    val spoken: String,
)

@Entity(tableName = "preserved_ranges", primaryKeys = ["jobId", "startMs"],
    foreignKeys = [ForeignKey(entity = DubJob::class, parentColumns = ["id"], childColumns = ["jobId"], onDelete = ForeignKey.CASCADE)])
data class PreservedRange(
    val jobId: String,
    val startMs: Long,
    val endMs: Long,
)

@Dao
interface JobsDao {
    @Query("SELECT * FROM jobs ORDER BY CASE WHEN state = 'preparing' OR state = 'downloading' THEN 0 WHEN state = 'queued' THEN 1 ELSE 2 END, queueOrder ASC, createdAt DESC")
    fun observe(): Flow<List<DubJob>>
    @Query("SELECT * FROM jobs ORDER BY createdAt DESC") suspend fun all(): List<DubJob>
    @Query("SELECT * FROM jobs WHERE id = :id") suspend fun get(id: String): DubJob?
    @Query("SELECT * FROM jobs WHERE state = 'queued' ORDER BY queueOrder ASC, createdAt ASC") suspend fun queued(): List<DubJob>
    @Upsert suspend fun save(job: DubJob)
    @Query("UPDATE jobs SET progress = :progress, message = :message WHERE id = :id")
    suspend fun progress(id: String, progress: Int, message: String)
    @Query("UPDATE jobs SET state = :state, message = :message WHERE id = :id")
    suspend fun state(id: String, state: String, message: String)
    @Query("UPDATE jobs SET previewState = :state, previewMessage = :message, previewAudioPath = :audioPath, previewStartMs = :startMs WHERE id = :id")
    suspend fun previewState(id: String, state: String, message: String, audioPath: String?, startMs: Long)
    @Query("UPDATE jobs SET decodedUntilMs = :until WHERE id = :id")
    suspend fun decoded(id: String, until: Long)
    @Query("UPDATE jobs SET mode = :mode, sourceLanguage = :language, state = 'new', progress = 0, message = 'Ready to prepare.' WHERE id = :id")
    suspend fun request(id: String, mode: String, language: String)
    @Query("UPDATE jobs SET mode = :mode, sourceLanguage = :language, audioTrackIndex = :audioTrack, subtitleTrackIndex = :subtitleTrack, subtitleOffsetMs = :offsetMs, voiceName = :voiceName, overrideRecommendation = :overrideRec, chargingOnly = :chargingOnly, state = :state, progress = 0, message = :message WHERE id = :id")
    suspend fun configureSource(
        id: String,
        mode: String,
        language: String,
        audioTrack: Int,
        subtitleTrack: Int,
        offsetMs: Long,
        voiceName: String?,
        overrideRec: Boolean,
        chargingOnly: Boolean,
        state: String = "new",
        message: String = "Ready to prepare.",
    )
    @Query("UPDATE jobs SET queueOrder = :order, chargingOnly = :chargingOnly, state = :state, message = :message WHERE id = :id")
    suspend fun updateQueue(id: String, order: Int, chargingOnly: Boolean, state: String, message: String)
    @Query("UPDATE jobs SET backgroundVolume = :volume, captionsEnabled = :captions, captionSizeSp = :sizeSp, playbackPositionMs = :positionMs, voiceName = :voiceName WHERE id = :id")
    suspend fun updateViewingPreferences(id: String, volume: Float, captions: Boolean, sizeSp: Float, positionMs: Long, voiceName: String?)
    @Query("UPDATE jobs SET playbackPositionMs = :positionMs WHERE id = :id")
    suspend fun savePlaybackPosition(id: String, positionMs: Long)
    @Query("UPDATE cues SET english = NULL, pcmPath = NULL, rate = 0, spokenStartMs = 0, spokenEndMs = 0 WHERE jobId = :id")
    suspend fun invalidateTranslation(id: String)
    @Query("UPDATE cues SET pcmPath = NULL, rate = 0, spokenStartMs = 0, spokenEndMs = 0 WHERE jobId = :id")
    suspend fun invalidateVoice(id: String)
    @Query("DELETE FROM jobs WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT * FROM cues WHERE jobId = :id ORDER BY `index`") suspend fun cues(id: String): List<SavedCue>
    @Upsert suspend fun saveCue(cue: SavedCue)
    @Upsert suspend fun saveCues(cues: List<SavedCue>)
    @Query("UPDATE cues SET editedEnglish = :editedEnglish, startOverrideMs = :startOverrideMs, endOverrideMs = :endOverrideMs, subtitlesOnly = :subtitlesOnly WHERE jobId = :jobId AND `index` = :index")
    suspend fun updateCueOverride(jobId: String, index: Int, editedEnglish: String?, startOverrideMs: Long?, endOverrideMs: Long?, subtitlesOnly: Boolean)
    @Query("DELETE FROM cues WHERE jobId = :id") suspend fun clearCues(id: String)
    @Query("SELECT * FROM pronunciations WHERE jobId = :jobId ORDER BY original")
    suspend fun pronunciations(jobId: String): List<PronunciationEntry>
    @Upsert suspend fun savePronunciation(entry: PronunciationEntry)
    @Query("DELETE FROM pronunciations WHERE jobId = :jobId AND original = :original")
    suspend fun deletePronunciation(jobId: String, original: String)
    @Query("SELECT * FROM preserved_ranges WHERE jobId = :jobId ORDER BY startMs")
    suspend fun preservedRanges(jobId: String): List<PreservedRange>
    @Upsert suspend fun savePreservedRange(range: PreservedRange)
    @Query("DELETE FROM preserved_ranges WHERE jobId = :jobId AND startMs = :startMs")
    suspend fun deletePreservedRange(jobId: String, startMs: Long)
    @Query("UPDATE jobs SET state = 'paused', message = 'Preparation interrupted. Tap Resume to continue.' WHERE state IN ('preparing', 'downloading')")
    suspend fun recoverInterrupted()
}

@Database(entities = [DubJob::class, SavedCue::class, PronunciationEntry::class, PreservedRange::class], version = 3, exportSchema = false)
abstract class DubDatabase : RoomDatabase() {
    abstract fun jobs(): JobsDao
    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN preparationSignature TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN voiceSignature TEXT DEFAULT NULL")
            }
        }
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jobs ADD COLUMN audioTrackIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE jobs ADD COLUMN subtitleTrackIndex INTEGER NOT NULL DEFAULT -1")
                db.execSQL("ALTER TABLE jobs ADD COLUMN subtitleOffsetMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN overrideRecommendation INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN voiceName TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN recognitionSignature TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN translationSignature TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN timelineSignature TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN previewAudioPath TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE jobs ADD COLUMN previewStartMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN previewState TEXT NOT NULL DEFAULT 'none'")
                db.execSQL("ALTER TABLE jobs ADD COLUMN previewMessage TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE jobs ADD COLUMN queueOrder INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN chargingOnly INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE jobs ADD COLUMN backgroundVolume REAL NOT NULL DEFAULT 0.15")
                db.execSQL("ALTER TABLE jobs ADD COLUMN captionsEnabled INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE jobs ADD COLUMN captionSizeSp REAL NOT NULL DEFAULT 16.0")
                db.execSQL("ALTER TABLE jobs ADD COLUMN playbackPositionMs INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cues ADD COLUMN editedEnglish TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE cues ADD COLUMN startOverrideMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE cues ADD COLUMN endOverrideMs INTEGER DEFAULT NULL")
                db.execSQL("ALTER TABLE cues ADD COLUMN subtitlesOnly INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE cues ADD COLUMN transcriptAvailable INTEGER NOT NULL DEFAULT 1")
                db.execSQL("CREATE TABLE IF NOT EXISTS `pronunciations` (`jobId` TEXT NOT NULL, `original` TEXT NOT NULL, `spoken` TEXT NOT NULL, PRIMARY KEY(`jobId`, `original`), FOREIGN KEY(`jobId`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
                db.execSQL("CREATE TABLE IF NOT EXISTS `preserved_ranges` (`jobId` TEXT NOT NULL, `startMs` INTEGER NOT NULL, `endMs` INTEGER NOT NULL, PRIMARY KEY(`jobId`, `startMs`), FOREIGN KEY(`jobId`) REFERENCES `jobs`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE)")
            }
        }
        val ALL_MIGRATIONS = arrayOf(MIGRATION_1_2, MIGRATION_2_3)
    }
}

