package kotlinx.coroutines.flow

interface FlowCollector<in T> { suspend fun emit(value: T) }

interface Flow<out T> { suspend fun collect(collector: FlowCollector<T>) }

fun <T> flow(block: suspend FlowCollector<T>.() -> Unit): Flow<T> = object : Flow<T> {
    override suspend fun collect(collector: FlowCollector<T>) { collector.block() }
}

fun <T> Flow<T>.flowOn(context: Any?): Flow<T> = this

fun <T> Flow<T>.catch(action: suspend FlowCollector<T>.(Throwable) -> Unit): Flow<T> = object : Flow<T> {
    override suspend fun collect(collector: FlowCollector<T>) {
        try {
            this@catch.collect(collector)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            collector.action(e)
        }
    }
}
