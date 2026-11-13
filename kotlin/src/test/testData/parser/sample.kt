@file:JvmName("Sample")
package demo.app

import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max as maximum

/** KDoc with [links]. */
sealed interface Shape { fun area(): Double }
data class Circle(val r: Double) : Shape { override fun area() = Math.PI * r * r }
enum class Color(val hex: String) { RED("#f00"), GREEN("#0f0"); fun label() = name.lowercase() }
object Registry { private val items = ConcurrentHashMap<String, Shape>(); operator fun get(k: String) = items[k] }

class Box<T : Comparable<T>>(private var value: T) {
    companion object { const val MAX = 10; fun <T : Comparable<T>> of(v: T) = Box(v) }
    val size: Int get() = 1
    infix fun bigger(other: Box<T>): Box<T> = if (value > other.value) this else other
}

typealias Handler = suspend (String) -> Unit

fun String.shout(): String = uppercase() + "!"

suspend fun main(args: Array<String>) {
    val xs = listOf(1, 2, 3).map { it * 2 }.filter { x -> x > 2 }
    val name: String? = args.firstOrNull()
    val n = name?.length ?: 0
    when (val s: Shape = Circle(1.0)) {
        is Circle -> println("circle ${s.r} ${s.area()}")
        else -> {}
    }
    for ((i, v) in xs.withIndex()) println("$i=$v")
    val (a, b) = Pair(1, "x")
    val box = Box.of(3) bigger Box(4)
    println("${"nested ${a + n}"} ${b.shout()} ${maximum(1, 2)} ${box.size}")
    try { error("x") } catch (e: IllegalStateException) { throw e } finally { }
    val lambda: (Int) -> Int = fun(x: Int): Int { return x + 1 }
    val ref = String::length
    context(Color.RED) { }
    label@ for (c in "abc") { if (c == 'b') continue@label }
}
