package playground

fun main() {
    val shapes = listOf(Circle(1.0).scaled(2.0), Rectangle(3.0, 4.0))
    println(shapes.joinToString { it.name.uppercase() })
    shapes.largest()?.let { println("largest: ${it.name} (${"%.2f".format(it.area())})") }
    val (radius) = Circle(3.0)
    println(radius.toInt())
}
