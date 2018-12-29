package arrow.effects.internal

import arrow.Kind
import arrow.core.*
import arrow.effects.MVar
import arrow.effects.typeclasses.Async
import arrow.effects.typeclasses.rightUnit
import arrow.effects.typeclasses.unitCallback
import java.util.concurrent.atomic.AtomicReference

//[MVar] implementation for [Async] data types.
internal class MVarAsync<F, A> private constructor(initial: State<A>, AS: Async<F>) : MVar<F, A>, Async<F> by AS {

  private val stateRef = AtomicReference<State<A>>(initial)

  override fun put(a: A): Kind<F, Unit> =
    tryPut(a).flatMap { result ->
      if (result) unit()
      else asyncF { cb -> unsafePut(a, cb) }
    }

  override fun tryPut(a: A): Kind<F, Boolean> =
    defer { unsafeTryPut(a) }

  override fun take(): Kind<F, A> =
    tryTake().flatMap {
      it.fold({ asyncF(::unsafeTake) }, ::just)
    }

  override fun tryTake(): Kind<F, Option<A>> =
    defer(::unsafeTryTake)

  override fun read(): Kind<F, A> =
    async(::unsafeRead)

  override fun isEmpty(): Kind<F, Boolean> = delay {
    when (stateRef.get()) {
      is State.WaitForPut -> true
      is State.WaitForTake -> false
    }
  }

  override fun isNotEmpty(): Kind<F, Boolean> =
    isEmpty().map { !it }

  private tailrec fun unsafeTryPut(a: A): Kind<F, Boolean> =
    when (val current = stateRef.get()) {
      is State.WaitForTake -> justFalse
      is State.WaitForPut -> {
        val first: Listener<A>? = current.takes.firstOrNull()
        val update: State<A> =
          if (current.takes.isEmpty()) State(a) else {
            val rest = current.takes.drop(1)
            if (rest.isEmpty()) State.empty()
            else State.WaitForPut(emptyList(), rest)
          }

        if (!stateRef.compareAndSet(current, update)) unsafeTryPut(a) // retry
        else if (first != null && current.reads.isNotEmpty()) streamPutAndReads(a, current.reads, first)
        else justTrue
      }
    }

  private tailrec fun unsafePut(a: A, onPut: Listener<Unit>): Kind<F, Unit> =
    when (val current = stateRef.get()) {
      is State.WaitForTake -> {
        val update = State.WaitForTake(current.value, current.puts + Tuple2(a, onPut))
        if (!stateRef.compareAndSet(current, update)) unsafePut(a, onPut) // retry
        else unit()
      }

      is State.WaitForPut -> {
        val first: Listener<A>? = current.takes.firstOrNull()
        val update: State<A> =
          if (current.takes.isEmpty()) State(a) else {
            val rest = current.takes.drop(1)
            if (rest.isEmpty()) State.empty()
            else State.WaitForPut(emptyList(), rest)
          }

        if (!stateRef.compareAndSet(current, update)) unsafePut(a, onPut) // retry
        else streamPutAndReads(a, current.reads, first).map { _ -> onPut(rightUnit) }
      }
    }

  private tailrec fun unsafeTryTake(): Kind<F, Option<A>> =
    when (val current = stateRef.get()) {
      is State.WaitForTake ->
        if (current.puts.isEmpty()) {
          if (stateRef.compareAndSet(current, State.empty())) just(Some(current.value)) // Signals completion of `take`
          else unsafeTryTake() // retry
        } else {
          val (ax, notify) = current.puts.first()
          val xs = current.puts.drop(1)
          val update = State.WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            asyncBoundary.map { _ ->
              notify(rightUnit) // Complete the `put` request waiting on a notification
              Some(current.value) // Signals completion of `take`
            }
          } else unsafeTryTake() // retry
        }

      is State.WaitForPut -> justNone
    }

  private tailrec fun unsafeTake(onTake: Listener<A>): Kind<F, Unit> =
    when (val current = stateRef.get()) {
      is State.WaitForTake ->
        if (current.puts.isEmpty()) {
          if (stateRef.compareAndSet(current, State.empty())) onTake(Right(current.value)).run { unit() } // Signals completion of `take`
          else unsafeTake(onTake) // retry
        } else {
          val (ax, notify) = current.puts.first()
          val xs = current.puts.drop(1)
          val update = State.WaitForTake(ax, xs)
          if (stateRef.compareAndSet(current, update)) {
            asyncBoundary.map { _ ->
              try {
                notify(rightUnit)
              } // Signals completion of `take`
              finally {
                onTake(Right(current.value))
              } // Signals completion of `take`
            }
          } else unsafeTake(onTake) // retry
        }

      is State.WaitForPut ->
        if (!stateRef.compareAndSet(current, State.WaitForPut(current.reads, current.takes + onTake))) unsafeTake(onTake)
        else unit()
    }

  private tailrec fun unsafeRead(onRead: Listener<A>): Unit =
    when (val current = stateRef.get()) {
      is State.WaitForTake ->
        // A value is available, so complete `read` immediately without
        // changing the sate
        onRead(Right(current.value))

      is State.WaitForPut ->
        // No value available, enqueue the callback
        if (!stateRef.compareAndSet(current, State.WaitForPut(current.reads + onRead, current.takes))) unsafeRead(onRead) // retry
        else Unit
    }

  private fun streamPutAndReads(a: A, reads: List<Listener<A>>, first: Listener<A>?): Kind<F, Boolean> =
    asyncBoundary.map { _ ->
      val value = Right(a)
      reads.forEach { it.invoke(value) } // Satisfies all current `read` requests found
      first?.invoke(value)
      true
    }

  private val justNone = just(None)
  private val justFalse = just(false)
  private val justTrue = just(true)
  private val asyncBoundary = async(unitCallback)

  companion object {
    /** Builds an [MVarAsync] instance with an [initial] value. */
    operator fun <F, A> invoke(initial: A, AS: Async<F>): Kind<F, MVar<F, A>> = AS.delay {
      MVarAsync(State(initial), AS)
    }

    /** Returns an empty [MVarAsync] instance. */
    fun <F, A> empty(AS: Async<F>): Kind<F, MVar<F, A>> = AS.delay {
      MVarAsync(State.empty<A>(), AS)
    }

    /** Internal state of [MVar]. */
    private sealed class State<A> {

      companion object {
        operator fun <A> invoke(a: A): State<A> = WaitForTake(a, emptyList())

        private val ref = WaitForPut<Any>(emptyList(), emptyList())
        fun <A> empty(): State<A> = ref as State<A>
      }

      /**
       * [MVarAsync] state signaling it has [take] callbacks registered
       * and we are waiting for one or multiple [put] operations.
       *
       * @param takes are the rest of the requests waiting in line,
       *        if more than one `take` requests were registered
       */
      data class WaitForPut<A>(val reads: List<Listener<A>>, val takes: List<Listener<A>>) : State<A>()

      /**
       * [MVarAsync] state signaling it has one or more values enqueued,
       * to be signaled on the next [take].
       *
       * @param value is the first value to signal
       * @param puts are the rest of the `put` requests, along with the
       *        callbacks that need to be called whenever the corresponding
       *        value is first in line (i.e. when the corresponding `put`
       *        is unblocked from the user's point of view)
       */
      data class WaitForTake<A>(val value: A, val puts: List<Tuple2<A, Listener<Unit>>>) : State<A>()
    }

  }
}

/**
 * Internal API — Matches the callback type in `cats.effect.Async`,
 * but we don't care about the error.
 */
private typealias Listener<A> = (Either<Nothing, A>) -> Unit
