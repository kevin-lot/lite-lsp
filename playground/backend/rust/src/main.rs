use playground::{largest, Circle, Rectangle, Shape};

fn main() {
    let shapes: Vec<Box<dyn Shape>> = vec![
        Box::new(Circle { radius: 1.0 }.scaled(2.0)),
        Box::new(Rectangle { width: 3.0, height: 4.0 }),
    ];
    let names: Vec<String> = shapes.iter().map(|s| s.name().to_uppercase()).collect();
    println!("{}", names.join(", "));
    if let Some(big) = largest(&shapes) {
        println!("largest: {} ({:.2})", big.name(), big.area());
    }
}
