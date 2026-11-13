import math
from abc import ABC, abstractmethod
from dataclasses import dataclass


class Shape(ABC):
    """Base class: Ctrl+Click on `area` from a subclass or an instance."""

    def __init__(self, name: str) -> None:
        self.name = name

    @abstractmethod
    def area(self) -> float: ...

    def describe(self) -> str:
        return f"{self.name}: {self.area():.2f}"


class Circle(Shape):
    def __init__(self, radius: float) -> None:
        super().__init__("circle")
        self.radius = radius

    def area(self) -> float:
        return math.pi * self.radius ** 2

    def scaled(self, factor: float) -> "Circle":
        return Circle(self.radius * factor)


class Rectangle(Shape):
    def __init__(self, width: float, height: float) -> None:
        super().__init__("rectangle")
        self.width = width
        self.height = height

    def area(self) -> float:
        return self.width * self.height


@dataclass
class Point:
    x: float
    y: float

    def distance_to(self, other: "Point") -> float:
        return math.hypot(self.x - other.x, self.y - other.y)
