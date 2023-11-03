int MAX_SIZE = 50 + 1; // 増やすことができる
int MAX_COUNT = 60 * 5; // 短くすることができる
int[] xs = new int[MAX_SIZE];
int[] ys = new int[MAX_SIZE];
int[] count = new int[MAX_SIZE];
int begin = 0;
int end = 0;
int lastDraw = 0;

void setup() {
  size(600, 400);
}

void draw() {
  background(255);
  noStroke();
  
  for (int i = begin; i != end; i = (i + 1) % MAX_SIZE) {
    float c = (frameCount - count[i]) / (float)MAX_COUNT;
    fill(255, 255 * c, 255 * c); 
    circle(xs[i], ys[i], 20);
  }

  int newBegin = begin;
  for (int i = begin; i != end; i = (i + 1) % MAX_SIZE) {
    if (count[i] + MAX_COUNT < frameCount) {
      newBegin = (newBegin + 1) % MAX_SIZE;
    }
  }
  begin = newBegin;
  if(frameCount > 300) {
    // throw new RuntimeException();
  }
  
}  

void mouseDragged() {
  if (frameCount - lastDraw < 2) {
    return;
  }
  lastDraw = frameCount;

  if (begin == (end + 1) % MAX_SIZE) {
    begin = (begin + 1) % MAX_SIZE;
  }
 
  xs[end] = mouseX;
  ys[end] = mouseY;
  count[end] = frameCount;
  end = (end + 1) % MAX_SIZE;
}
