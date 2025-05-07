void triangle(Turtle turtle, double size) {
    turtle.forward(size).right(60).backward(size).right(60).forward(size).right(60 + 180);
}

void drawing(Turtle turtle, double size) {
    for (int i = 1; i <= 36; i++) {
        turtle.color(255, i * 256 / 37, i * 256 / 37, 1);
        turtle.width(1.0 - 1.0 / 36.0 * i);
        triangle(turtle, size + 1 - 2 * i);
        turtle.left(10).forward(10);
    }
}

void main() throws IOException {
    var turtle = new Turtle(0, 300, 0, 350, 150, 175, 0);
    drawing(turtle, 100);
    turtle.save();
}