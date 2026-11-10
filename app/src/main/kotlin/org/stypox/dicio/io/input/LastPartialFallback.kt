package org.stypox.dicio.io.input

/**
 * Wraps a downstream [InputEvent] listener for one listening session.
 *
 * A [InputEvent.None] at the end of the session becomes
 * [InputEvent.Final] with the last non-blank partial when one exists, so a
 * recognized utterance is not dropped. A real [InputEvent.Final] or
 * [InputEvent.Error] is forwarded unchanged. After the first terminal event
 * the session is finished and further events are ignored.
 */
class LastPartialFallback(
    private val downstream: (InputEvent) -> Unit,
) : (InputEvent) -> Unit {

    var promoted: Boolean = false
        private set

    private var lastPartial: String? = null
    private var finished: Boolean = false

    override fun invoke(event: InputEvent) {
        when (event) {
            is InputEvent.Partial -> {
                if (finished) return
                val trimmed = event.utterance.trim()
                if (trimmed.isNotEmpty()) {
                    lastPartial = trimmed
                }
                downstream(event)
            }
            InputEvent.None -> {
                if (finished) return
                finished = true
                val delivered = withLastPartial(event, lastPartial)
                if (delivered is InputEvent.Final) {
                    promoted = true
                }
                downstream(delivered)
            }
            is InputEvent.Final, is InputEvent.Error -> {
                if (finished) return
                finished = true
                downstream(event)
            }
        }
    }

    companion object {
        /**
         * Returns [InputEvent.Final] with the trimmed [lastPartial] only when
         * [event] is [InputEvent.None] and [lastPartial] is non-blank.
         * [InputEvent.Final], [InputEvent.Error] and [InputEvent.Partial] are
         * returned unchanged.
         */
        fun withLastPartial(event: InputEvent, lastPartial: String?): InputEvent {
            if (event !is InputEvent.None) return event
            val trimmed = lastPartial?.trim().orEmpty()
            if (trimmed.isEmpty()) return InputEvent.None
            return InputEvent.Final(listOf(trimmed to 1.0f))
        }
    }
}
