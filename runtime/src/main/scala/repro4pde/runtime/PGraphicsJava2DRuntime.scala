package repro4pde.runtime;

import processing.core.PSurface

class PGraphicsJava2DRuntime extends PGraphicsJava2DDummyImpl {
  override def createSurface(): PSurface = {
    this.surface = new PSurfaceAWTRuntime(this);
    this.surface
  }

  override def beginDraw(): Unit = {
    super.beginDraw();
    if (RuntimeMain.sketchHandler.enableDraw) {
      this.vertexCount = 0
    }

  }

  override def endDraw(): Unit = {
    if (RuntimeMain.sketchHandler.enableDraw) {
      super.endDraw()
    }
  }
}
