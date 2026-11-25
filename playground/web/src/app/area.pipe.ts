import { Pipe, PipeTransform } from '@angular/core';

@Pipe({ name: 'area' })
export class AreaPipe implements PipeTransform {
    transform(value: number, unit: string): string {
        return `${value.toFixed(2)} ${unit}`;
    }
}
