/// Exported so a JVM module in the same build can call it.
/// Only primitives: the pinned Flix release accepts nothing wider here.
mod Acme.Api {
    @Export
    pub def add(x: Int32, y: Int32): Int32 = x + y
}

def main(): Unit \ IO =
    println("Hello World!")
