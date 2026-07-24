package build.raft.app.data

/**
 * task #354: process-wide in-process transport for host→Kuikly and
 * page→page notify events on Android.
 *
 * The app is single-process, so the previous route — Kuikly's
 * `KRNotifyModule.sendKREvent` system broadcast with the whole event JSON in
 * an Intent extra — bought nothing and crashed with
 * TransactionTooLargeException once a realtime event outgrew the ~1 MB Binder
 * parcel limit (Hands 143077f5: 1,628,916 bytes). Events published here are
 * delivered as the same immutable [String] reference to every listener: zero
 * Binder, zero copies, no size limit, no chunking.
 *
 * Threading contract: [publish], [register] and [unregister] run on the main
 * thread — the same delivery thread the replaced broadcast receiver used, so
 * listener callback threading is unchanged.
 */
object SlockKuiklyEventHub {
    fun interface Listener {
        fun onEvent(eventName: String, payloadJson: String)
    }

    private val listeners = LinkedHashSet<Listener>()

    fun register(listener: Listener) {
        listeners.add(listener)
    }

    fun unregister(listener: Listener) {
        listeners.remove(listener)
    }

    fun listenerCount(): Int = listeners.size

    fun publish(eventName: String, payloadJson: String) {
        if (listeners.isEmpty()) return
        // Snapshot: a callback may register/unregister listeners re-entrantly
        // (page open/close in response to an event).
        listeners.toList().forEach { listener ->
            listener.onEvent(eventName, payloadJson)
        }
    }
}
