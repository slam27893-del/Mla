// Minimal stand-ins for the Android / coroutines / serialization APIs that the
// production files import. They exist only so the REAL MyAI sources can be
// compiled and executed on a plain JVM in this sandbox.
// Nothing here is part of the app; it is never committed.
package kotlinx.serialization

@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.RUNTIME)
annotation class Serializable
