package arrow.fx.typeclasses

import arrow.Kind
import arrow.core.Tuple2
import arrow.typeclasses.Applicative
import kotlin.coroutines.CoroutineContext

@Suppress("FunctionName", "DELEGATED_MEMBER_HIDES_SUPERTYPE_OVERRIDE", "ObjectPropertyName")
internal fun <F> Concurrent<F>.ParApplicative(ctx: CoroutineContext? = null): Applicative<F> = object : Concurrent<F> by this {

  val _ctx = ctx ?: dispatchers().default()

  override fun <A, B, Z> Kind<F, A>.map2(fb: Kind<F, B>, f: (Tuple2<A, B>) -> Z): Kind<F, Z> =
    _ctx.parMapN(this@map2, fb) { a, b -> f(Tuple2(a, b)) }

  override fun <A, B> Kind<F, A>.ap(ff: Kind<F, (A) -> B>): Kind<F, B> =
    _ctx.parMapN(this@ap, ff) { a, f -> f(a) }

  override fun <A, B> Kind<F, A>.product(fb: Kind<F, B>): Kind<F, Tuple2<A, B>> =
    _ctx.parMapN(this@product, fb, ::Tuple2)
}