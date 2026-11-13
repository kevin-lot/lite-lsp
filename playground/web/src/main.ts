import { readFileSync } from "node:fs";
import { Circle, Point, Rectangle, Registry, Unit, type Shape } from "./shapes/index.js";
import { formatArea, separator } from "./legacy/format.cjs";

const registry = new Registry<Shape>()
  .add(new Circle(new Point(0, 0), 2))
  .add(new Rectangle(new Point(1, 1), 3, 4), Unit.Millimeter);

const lines = registry.all().map((shape) => formatArea(shape.area(), Unit.Centimeter));
console.log(lines.join(separator));

const biggest = registry.largest();
console.log(biggest?.describe() ?? "empty");

const { name } = registry.get("circle") ?? { name: "none" };
const circle = new Circle(new Point(3, 4), 1).scaled(2);
console.log(name, circle.center.distanceTo(new Point(0, 0)));

export async function loadFrom(path: string): Promise<number> {
  return registry.load(async () => JSON.parse(readFileSync(path, "utf8")).map((r: number) => new Circle(new Point(0, 0), r)));
}
