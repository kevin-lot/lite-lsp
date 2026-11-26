sealed class Shape {
  final String name;
  const Shape(this.name);
  double get area;
}

class Square extends Shape {
  final double side;
  const Square(this.side) : super('square');
  @override
  double get area => side * side;
}

class Circle extends Shape {
  final double radius;
  const Circle({required this.radius}) : super('circle');
  @override
  double get area => 3.14159 * radius * radius;
}

Shape largest(List<Shape> shapes) => shapes.reduce((a, b) => a.area > b.area ? a : b);
