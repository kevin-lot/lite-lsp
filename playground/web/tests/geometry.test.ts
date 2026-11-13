import { test } from "node:test";
import assert from "node:assert/strict";
import { Circle, Point, Registry, type Shape } from "../src/shapes/index.js";

test("circle area", () => {
  assert.equal(new Circle(new Point(0, 0), 1).area().toFixed(3), "3.142");
});

test("registry keeps the largest shape", () => {
  const registry = new Registry<Shape>().add(new Circle(new Point(0, 0), 1)).add(new Circle(new Point(0, 0), 3));
  assert.equal(registry.largest()?.name, "circle");
});
