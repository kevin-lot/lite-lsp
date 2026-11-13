import type { Shape } from "../shapes/index.js";

/** JSX without React: `jsx.d.ts` declares the intrinsic elements. */
export function Badge({ shape }: { shape: Shape }) {
  return <span className="badge" title={shape.describe()}>{shape.name}</span>;
}

export const List = ({ shapes }: { shapes: Shape[] }) => (
  <ul>
    {shapes.map((s) => (
      <li key={s.name}>
        <Badge shape={s} />
      </li>
    ))}
  </ul>
);
