//! Shapes shared by the playground binary.
pub mod shapes;

pub use shapes::{Circle, Rectangle, Shape};

/// The largest shape by area, if any.
pub fn largest(shapes: &[Box<dyn Shape>]) -> Option<&dyn Shape> {
    shapes.iter().map(|s| s.as_ref()).max_by(|a, b| a.area().total_cmp(&b.area()))
}
