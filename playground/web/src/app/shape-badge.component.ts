import { Component, EventEmitter, Input, Output } from '@angular/core';

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
    @Input() label = 'circle';
    large = false;
    @Output() toggled = new EventEmitter<boolean>();

    toggle(): void {
        this.large = !this.large;
        this.toggled.emit(this.large);
    }
}
