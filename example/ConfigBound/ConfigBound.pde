float x = 100;
float y = 100;
float dx = 3;
float dy = 0;
float r = 50;

void setup() {
  size(600, 400);
}

void draw() {
  background(255);
  x += dx;
  y += dy;
  dy += 0.1;
  if (x < r && 0 > dx) {
    dx *= -1;
  }
  if (width - r < x && 0 < dx) {
    dx *= -1;
  }
  if (height - r < y && 0 < dy) {
    dy *= -1;
  }
  circle(x, y, 2 * r);
}
