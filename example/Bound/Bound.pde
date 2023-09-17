float x = 100;
float y = 100;
float dx = 3;
float dy = 0;

void setup() {
  size(600, 400);
}

void draw() {
  background(255);
  x += dx;
  y += dy;
  dy += 0.1;
  if (x < 0) {
    dx *= -1;
    x = 0;
  }
  if (width < x) {
    dx *= -1;
    x = width;
  }
  if (height < y) {
    dy *= -0.9; // ここの値を変える
    y = height;
  }
  circle(x, y, 100);
}
