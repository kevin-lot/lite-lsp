from shapes import Circle, Rectangle
from shapes.registry import default_registry


def test_circle_area():
    assert round(Circle(1.0).area(), 2) == 3.14


def test_largest():
    registry = default_registry()
    registry.add(Rectangle(10, 10))
    largest = registry.largest()
    assert largest is not None and largest.name == "rectangle"
