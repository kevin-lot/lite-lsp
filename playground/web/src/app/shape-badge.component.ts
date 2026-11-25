import { Component } from '@angular/core';

@Component({
    selector: 'app-shape-badge',
    template: `
        <span #badge [class.large]="large" (click)="toggle()">{{ label | uppercase }}</span>
        @if (large) {
            <small>{{ label.length }} letters</small>
        }
    `,
})
export class ShapeBadgeComponent {
    label = 'circle';
    large = false;

    toggle(): void {
        this.large = !this.large;
    }
}
