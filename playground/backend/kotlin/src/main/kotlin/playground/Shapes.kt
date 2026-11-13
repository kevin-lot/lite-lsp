package playground

sealed interface Shape {
    val name: String
    fun area(): Double
}

data class Circle(val radius: Double) : Shape {
    override val name = "circle"
    override fun area() = Math.PI * radius * radius
}

data class Rectangle(val width: Double, val height: Double) : Shape {
    override val name = "rectangle"
    override fun area() = width * height
}

/** An extension: Ctrl+Click on `largest()` in Main.kt lands here. */
fun List<Shape>.largest(): Shape? = maxByOrNull { it.area() }

fun Circle.scaled(factor: Double) = copy(radius = radius * factor)
