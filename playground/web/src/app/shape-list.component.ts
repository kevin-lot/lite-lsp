import { Component } from '@angular/core';

interface Shape {
    id: number;
    name: string;
    area: number;
}

@Component({ selector: 'app-shape-list', templateUrl: './shape-list.component.html' })
export class ShapeListComponent {
    busy = false;
    shapes: Shape[] = [];
    title = 'Shapes';
    updated = new Date();

    remove(shape: Shape): void {
        this.shapes = this.shapes.filter((s) => s.id !== shape.id);
    }
}
