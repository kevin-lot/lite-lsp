use std::fmt;

pub trait Shape: fmt::Debug {
    fn name(&self) -> &str;
    fn area(&self) -> f64;
}

#[derive(Debug, Clone, Copy)]
pub struct Circle {
    pub radius: f64,
}

#[derive(Debug, Clone, Copy)]
pub struct Rectangle {
    pub width: f64,
    pub height: f64,
}

impl Shape for Circle {
    fn name(&self) -> &str { "circle" }
    fn area(&self) -> f64 { std::f64::consts::PI * self.radius * self.radius }
}

impl Shape for Rectangle {
    fn name(&self) -> &str { "rectangle" }
    fn area(&self) -> f64 { self.width * self.height }
}

impl Circle {
    pub fn scaled(self, factor: f64) -> Circle {
        Circle { radius: self.radius * factor }
    }
}
