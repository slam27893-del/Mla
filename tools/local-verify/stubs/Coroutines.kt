package kotlinx.coroutines

typealias CancellationException = java.util.concurrent.CancellationException

object Dispatchers { val IO: Any = Any() }

suspend fun <T> withTimeoutOrNull(timeMillis: Long, block: suspend () -> T): T? = block()

class Job
class SupervisorJob
