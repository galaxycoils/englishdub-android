package app.englishdub

import android.app.Application
import androidx.room.Room
import app.englishdub.data.DubDatabase
import app.englishdub.engine.ModelStore
import app.englishdub.engine.ResourcePolicy
import java.io.File
import kotlinx.coroutines.*

class DubApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, DubDatabase::class.java, "englishdub.db").addMigrations(*DubDatabase.ALL_MIGRATIONS).build() }
    val jobs get() = database.jobs()
    val models by lazy { ModelStore(this) }
    val speechCacheDir by lazy { File(filesDir, "speech-cache").apply { mkdirs() } }
    fun jobDirectory(id: String) = File(filesDir, "jobs/$id").apply { mkdirs() }
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val recovery by lazy { applicationScope.async {
        jobs.recoverInterrupted()
        jobs.all().forEach { job -> ResourcePolicy.removeUnusedDubs(jobDirectory(job.id), job.audioPath) }
    } }
    override fun onCreate() { super.onCreate(); recovery.start() }
}
