# typescript_template

Template to try the Lite plugin on TypeScript and JavaScript: open it in the workspace, then

- Ctrl+Click: `registry.all().map((shape) => shape.area())` (main.ts) → callback parameter typed from
  `Registry<Shape>`, `circle.center.distanceTo` (constructor parameter property), `biggest?.describe()`
  (inherited from `BaseShape`), `./shapes/index.js` (→ `index.ts`), `formatArea` (CommonJS `format.cjs`),
  `<Badge shape={s} />` (ui/Badge.tsx), `readFileSync` and `JSON.parse` (after `npm install`).
- Rename `area` from `Shape`: implementations and calls follow.
- Find Usages on `Registry.add` with different scopes.

Library navigation (Node and TypeScript standard library): `npm install` (only the declared packages are indexed).
Type-check: `npm run check`. Tests: `npm test`.
