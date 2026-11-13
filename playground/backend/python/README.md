# python_template

Template to try the Lite plugin on Python: open it in the workspace, then

- Ctrl+Click: `shape.describe()` (registry.py) → base class method, `self.radius`, `Circle(...).scaled(...)`,
  `from .geometry import Circle`, `path.write_text` (stdlib), `largest.name` (Optional[Shape] annotation).
- Rename `area` from `Shape`: subclasses and calls follow.
- Find Usages on `Registry.add` with different scopes.

Optional, for library navigation into a virtualenv: `python3 -m venv .venv`.
Run the tests (needs pytest): `python3 -m pytest`.
