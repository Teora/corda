package net.corda.core.utilities

import net.corda.core.serialization.CordaSerializable
import net.corda.core.utilities.Try.Failure
import net.corda.core.utilities.Try.Success

/**
 * Representation of an operation that has either succeeded with a result (represented by [Success]) or failed with an
 * exception (represented by [Failure]).
 */
@CordaSerializable
sealed class Try<out A> {
    companion object {
        /**
         * Executes the given block of code and returns a [Success] capturing the result, or a [Failure] if an exception
         * is thrown.
         */
        @JvmStatic
        inline fun <T> on(body: () -> T): Try<T> {
            return try {
                Success(body())
            } catch (t: Throwable) {
                Failure(t)
            }
        }
    }

    /** Returns `true` iff the [Try] is a [Success]. */
    abstract val isFailure: Boolean

    /** Returns `true` iff the [Try] is a [Failure]. */
    abstract val isSuccess: Boolean

    /** Returns the value if a [Success] otherwise throws the exception if a [Failure]. */
    abstract fun getOrThrow(): A

    /** Maps the given function to the value from this [Success], or returns `this` if this is a [Failure]. */
    @Suppress("UNCHECKED_CAST")
    inline fun <B> map(function: (A) -> B): Try<B> = when (this) {
        is Success -> Success(function(value))
        is Failure -> this as Try<B>
    }

    /** Returns the given function applied to the value from this [Success], or returns `this` if this is a [Failure]. */
    @Suppress("UNCHECKED_CAST")
    inline fun <B> flatMap(function: (A) -> Try<B>): Try<B> = when (this) {
        is Success -> function(value)
        is Failure -> this as Try<B>
    }

    /**
     * Maps the given function to the values from this [Success] and [other], or returns `this` if this is a [Failure]
     * or [other] if [other] is a [Failure].
     */
    @Suppress("UNCHECKED_CAST")
    inline fun <B, C> combine(other: Try<B>, function: (A, B) -> C): Try<C> = when (this) {
        is Success -> when (other) {
            is Success -> Success(function(value, other.value))
            is Failure -> other as Try<C>
        }
        is Failure -> this as Try<C>
    }

    data class Success<out A>(val value: A) : Try<A>() {
        override val isSuccess: Boolean get() = true
        override val isFailure: Boolean get() = false
        override fun getOrThrow(): A = value
        override fun toString(): String = "Success($value)"
    }

    data class Failure<out A>(val exception: Throwable) : Try<A>() {
        override val isSuccess: Boolean get() = false
        override val isFailure: Boolean get() = true
        override fun getOrThrow(): A = throw exception
        override fun toString(): String = "Failure($exception)"
    }
}
