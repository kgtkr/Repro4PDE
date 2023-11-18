// Langton's ant

int SIZE = 100;
boolean[][] grid = new boolean[SIZE][SIZE];
int x = SIZE / 2;
int y = SIZE / 2;
int dir = 0; // 0: down, 1: right, 2: up, 3: left
int GRID_SIZE = 10;
int state = 0; // ready, running, finished

void settings() {
  size(SIZE * GRID_SIZE, SIZE * GRID_SIZE);
}

void setup() {
}

void draw() {
  if (state == 1) {
    if (grid[y][x]) {
      dir = (dir + 1) % 4;
    } else {
      dir = (dir + 3) % 4;
    }
    grid[y][x] = !grid[y][x];

    if (dir == 0) {
      y++;
    } else if (dir == 1) {
      x++;
    } else if (dir == 2) {
      y--;
    } else if (dir == 3) {
      x--;
    }

    if (x < 0 || x >= SIZE || y < 0 || y >= SIZE) {
      // state = 2;
    }
  }

  background(255);
  for (int i = 0; i < SIZE; i++) {
    for (int j = 0; j < SIZE; j++) {
      if (grid[i][j]) {
        fill(0);
      } else {
        fill(255);
      }
      stroke(200);
      rect(j * GRID_SIZE, i * GRID_SIZE, GRID_SIZE, GRID_SIZE);
    }
  }

  noStroke();
  fill(255, 0, 0);
  circle(x * GRID_SIZE + GRID_SIZE / 2, y * GRID_SIZE + GRID_SIZE / 2, GRID_SIZE / 2);

  if (frameCount > 300) {
    throw new RuntimeException();
  }
}

void keyPressed() {
  if (key == ' ' && state == 0) {
    state = 1;
  }
}

void mouseDragged() {
  if (state == 0) {
    int i = mouseY / GRID_SIZE;
    int j = mouseX / GRID_SIZE;
    if (i >= 0 && i < SIZE && j >= 0 && j < SIZE) {
      grid[i][j] = true;
    }
  }
}
