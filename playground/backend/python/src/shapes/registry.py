from pathlib import Path
from typing import Optional

from .geometry import Circle, Shape


class Registry:
    def __init__(self) -> None:
        self.items: list[Shape] = []

    def add(self, shape: Shape) -> "Registry":
        self.items.append(shape)
        return self

    def largest(self) -> Optional[Shape]:
        return max(self.items, key=lambda s: s.area(), default=None)

    def report(self) -> str:
        lines = []
        for shape in self.items:
            lines.append(shape.describe())
        return "\n".join(lines)

    def save(self, path: Path) -> None:
        path.write_text(self.report())


def default_registry() -> Registry:
    registry = Registry()
    registry.add(Circle(1.0)).add(Circle(2.0).scaled(2))
    return registry
