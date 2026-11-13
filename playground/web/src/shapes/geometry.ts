/** Units a length can be expressed in. */
export enum Unit {
  Millimeter = "mm",
  Centimeter = "cm",
}

export interface Shape {
  readonly name: string;
  area(): number;
  describe(): string;
}

export class Point {
  constructor(public readonly x: number, public readonly y: number) {}

  distanceTo(other: Point): number {
    return Math.hypot(this.x - other.x, this.y - other.y);
  }
}

export abstract class BaseShape implements Shape {
  abstract readonly name: string;
  abstract area(): number;

  describe(): string {
    return `${this.name}: ${this.area().toFixed(2)}`;
  }
}

export class Circle extends BaseShape {
  readonly name = "circle";

  constructor(readonly center: Point, readonly radius: number) {
    super();
  }

  area(): number {
    return Math.PI * this.radius ** 2;
  }

  scaled(factor: number): Circle {
    return new Circle(this.center, this.radius * factor);
  }
}

export class Rectangle extends BaseShape {
  readonly name = "rectangle";

  constructor(readonly corner: Point, readonly width: number, readonly height: number) {
    super();
  }

  area(): number {
    return this.width * this.height;
  }
}

export type Measured<T extends Shape> = { shape: T; unit: Unit };
