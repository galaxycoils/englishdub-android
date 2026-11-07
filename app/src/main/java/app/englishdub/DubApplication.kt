package app.englishdub

import android.app.Application
import androidx.room.Room
import app.englishdub.data.DubDatabase
import app.englishdub.engine.ModelStore
import java.io.File

class DubApplication : Application() {
    val database by lazy { Room.databaseBuilder(this, DubDatabase::class.java, "englishdub.db").build() }
    val jobs get() = database.jobs()
    val models by lazy { ModelStore(this) }
    fun jobDirectory(id: String) = File(filesDir, "jobs/$id").apply { mkdirs() }
}
