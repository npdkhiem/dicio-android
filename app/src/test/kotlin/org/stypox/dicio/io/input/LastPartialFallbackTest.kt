package org.stypox.dicio.io.input

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

class LastPartialFallbackTest : StringSpec({
    "partials then None become a Final from the last partial" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.Partial("stop"))
        fallback(InputEvent.Partial("stop listening"))
        fallback(InputEvent.None)
        got.shouldContainExactly(
            InputEvent.Partial("stop"),
            InputEvent.Partial("stop listening"),
            InputEvent.Final(listOf("stop listening" to 1.0f)),
        )
        fallback.promoted shouldBe true
    }

    "None without a partial stays None" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.None)
        got.shouldContainExactly(InputEvent.None)
        fallback.promoted shouldBe false
    }

    "a blank partial then None stays None" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.Partial("  "))
        fallback(InputEvent.None)
        got.last() shouldBe InputEvent.None
        got.none { it is InputEvent.Final } shouldBe true
        fallback.promoted shouldBe false
    }

    "a real Final is kept and a later None is ignored" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        val real = InputEvent.Final(listOf("what time is it" to 0.9f))
        fallback(InputEvent.Partial("what time"))
        fallback(real)
        fallback(InputEvent.None)
        got.shouldContainExactly(InputEvent.Partial("what time"), real)
        fallback.promoted shouldBe false
    }

    "an Error is not promoted and a later None is ignored" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        val error = InputEvent.Error(UserFacingInputException("busy"))
        fallback(InputEvent.Partial("stop listening"))
        fallback(error)
        fallback(InputEvent.None)
        got.shouldContainExactly(InputEvent.Partial("stop listening"), error)
        got[1].shouldBeInstanceOf<InputEvent.Error>()
        fallback.promoted shouldBe false
    }

    "a second None after a promoted Final is ignored" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.Partial("stop listening"))
        fallback(InputEvent.None)
        fallback(InputEvent.None)
        got.shouldContainExactly(
            InputEvent.Partial("stop listening"),
            InputEvent.Final(listOf("stop listening" to 1.0f)),
        )
        fallback.promoted shouldBe true
    }

    "Vosk tap-to-stop after a partial is a Final" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.Partial("stop listening"))
        // stopListening(..., sendNoneEvent = true)
        fallback(InputEvent.None)
        got.shouldContainExactly(
            InputEvent.Partial("stop listening"),
            InputEvent.Final(listOf("stop listening" to 1.0f)),
        )
        fallback.promoted shouldBe true
    }

    "partials after the session has finished are dropped" {
        val got = mutableListOf<InputEvent>()
        val fallback = LastPartialFallback { got.add(it) }
        fallback(InputEvent.Partial("stop"))
        fallback(InputEvent.None)
        fallback(InputEvent.Partial("later"))
        got.shouldContainExactly(
            InputEvent.Partial("stop"),
            InputEvent.Final(listOf("stop" to 1.0f)),
        )
    }
})
