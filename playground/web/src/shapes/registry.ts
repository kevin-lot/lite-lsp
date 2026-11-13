import { type Shape, Unit, type Measured } from "./geometry.js";

/** Keeps shapes by name. */
export class Registry<T extends Shape> {
  private readonly items = new Map<string, Measured<T>>();

  add(shape: T, unit: Unit = Unit.Centimeter): this {
    this.items.set(shape.name, { shape, unit });
    return this;
  }

  get(name: string): T | undefined {
    return this.items.get(name)?.shape;
  }

  all(): T[] {
    return [...this.items.values()].map((m) => m.shape);
  }

  largest(): T | undefined {
    return this.all().reduce<T | undefined>((best, s) => (best === undefined || s.area() > best.area() ? s : best), undefined);
  }

  async load(source: () => Promise<T[]>): Promise<number> {
    const shapes = await source();
    shapes.forEach((s) => this.add(s));
    return shapes.length;
  }
}
