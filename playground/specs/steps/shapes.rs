use cucumber::{given, then, when};

#[given(expr = "an empty canvas")]
fn empty_canvas(world: &mut World) {}

#[given(expr = "a circle of radius {int}")]
fn circle(world: &mut World, radius: u32) {}

#[given(expr = "a square named {string} of side {int}")]
fn square(world: &mut World, name: String, side: u32) {}

#[given(regex = r"^a (\w+) on the canvas$")]
fn shape(world: &mut World, kind: String) {}

#[when(expr = "I ask for the largest shape")]
fn largest(world: &mut World) {}

#[then(expr = "the answer is {string}")]
fn answer(world: &mut World, name: String) {}

#[then(expr = "it has {int} sides")]
fn sides(world: &mut World, count: u32) {}
