package app.englishdub.engine

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PreparationStatus(val busy: Boolean = false, val id: String? = null, val message: String = "", val progress: Int = 0)

/** One admission owner, including the interval before Android delivers onStartCommand. */
class TaskAdmission {
    private var sequence = 0L
    private var owner: Long? = null
    @Synchronized fun reserve(): Long { check(owner == null) { "Pause the current task first." }; return (++sequence).also { owner = it } }
    @Synchronized fun owns(ticket: Long) = owner == ticket
    @Synchronized fun release(ticket: Long): Boolean { if (owner != ticket) return false; owner = null; return true }
}

object PreparationCoordinator {
    private val admission = TaskAdmission()
    private val mutable = MutableStateFlow(PreparationStatus())
    val status = mutable.asStateFlow()
    @Synchronized fun reserve(id: String): Long = admission.reserve().also { mutable.value = PreparationStatus(true, id, "Starting…") }
    fun owns(ticket: Long) = admission.owns(ticket)
    @Synchronized fun report(ticket: Long, status: PreparationStatus) { if (admission.owns(ticket)) mutable.value = status }
    @Synchronized fun finish(ticket: Long, message: String) { if (admission.release(ticket)) mutable.value = PreparationStatus(message = message) }
}
