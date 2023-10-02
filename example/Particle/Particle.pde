int QUEUE_SIZE = 50 + 1;
int LIFESPAN = 60 * 5;
int PARTICLE_COUNT = 36;
float PARTICLE_SPEED = 3;
float PARTICLE_SIZE = 10;
int[] xs = new int[QUEUE_SIZE];
int[] ys = new int[QUEUE_SIZE];
int[] frameCounts = new int[QUEUE_SIZE];
int begin = 0;
int end = 0;

void setup() {
  size(600, 400);
}

void draw() {
  background(255);
  noStroke();
  
  for (int i = begin; i != end; i = (i + 1) % QUEUE_SIZE) {
    fill(255, 0, 0);
    for (int j = 0; j < PARTICLE_COUNT; j++) {
      float theta = j * TWO_PI / PARTICLE_COUNT;
      float r = (frameCount - frameCounts[i]) * PARTICLE_SPEED;
      float x = xs[i] + r * cos(theta);
      float y = ys[i] + r * sin(theta);
      circle(x, y, PARTICLE_SIZE);
    }
  }

  int newBegin = begin;
  for (int i = begin; i != end; i = (i + 1) % QUEUE_SIZE) {
    if (frameCounts[i] + LIFESPAN < frameCount) {
      newBegin = (newBegin + 1) % QUEUE_SIZE;
    }
  }
  begin = newBegin;
  
}  

void mouseClicked() {
  if (begin == (end + 1) % QUEUE_SIZE) {
    begin = (begin + 1) % QUEUE_SIZE;
  }
 
  xs[end] = mouseX;
  ys[end] = mouseY;
  frameCounts[end] = frameCount;
  end = (end + 1) % QUEUE_SIZE;
}
