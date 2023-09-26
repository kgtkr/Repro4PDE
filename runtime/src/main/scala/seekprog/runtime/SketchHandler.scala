package seekprog.runtime;

import processing.core.PApplet;
import processing.event.MouseEvent
import processing.event.KeyEvent
import scala.collection.mutable.Buffer
import io.circe._, io.circe.generic.semiauto._
import scala.util.Try
import seekprog.shared.PdeEventWrapper
import seekprog.shared.RuntimeEvent

class SketchHandler(
    applet: PApplet,
    targetFrameCount: Int
) {
  var onTarget = false;
  val currentFrameEvents = Buffer[PdeEventWrapper]();
  val eventsBuf = Buffer[List[PdeEventWrapper]]();
  var stopReproductionEvent = false;
  var startTime = 0L;

  def pre() = {
    if (this.applet.frameCount == 1) {
      this.startTime = System.nanoTime();

      if (
        applet
          .sketchRenderer() != classOf[PGraphicsJava2DRuntime].getName()
      ) {
        throw new RuntimeException(
          "Seekprog not support renderer settings. size() must be two arguments."
        );
      }

      RuntimeMain.surface.disableEvent();
    }

    if (!this.onTarget && this.applet.frameCount >= this.targetFrameCount) {
      this.onTarget = true;
      if (!RuntimeMain.slaveMode) {
        RuntimeMain.surface.enableEvent();
      }
      val endTime = System.nanoTime();
      val ms = (endTime - this.startTime) / 1000000.0;
      RuntimeMain.log(
        "onTarget: " + ms + "ms, " +
          "targetFrameCount: " + this.targetFrameCount + ", " +
          "frameRate: " + this.targetFrameCount / ms * 1000
      );
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent.OnTargetFrameCount
      )
    }

    if (!this.stopReproductionEvent) {
      Try(RuntimeMain.events(this.applet.frameCount)).toOption
        .foreach {
          _.foreach {
            case PdeEventWrapper.Mouse(evt) =>
              this.applet.postEvent(ReproductionEvent.mouseEventToPde(evt));
            case PdeEventWrapper.Key(evt) =>
              this.applet.postEvent(ReproductionEvent.keyEventToPde(evt));
          };
        };
    }

    if (this.onTarget) {
      this.eventsBuf += this.currentFrameEvents.toList;
      this.currentFrameEvents.clear();
    }

    if (this.onTarget /*&& this.applet.frameCount % 10 == 0*/ ) {
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent
          .OnUpdateLocation(
            this.applet.frameCount,
            this.stopReproductionEvent,
            this.eventsBuf.toList,
            this.applet.windowX,
            this.applet.windowY
          )
      )
      this.eventsBuf.clear();
    }
  }

  def mouseEvent(evt: MouseEvent) = {
    if (this.onTarget) {
      this.currentFrameEvents +=
        PdeEventWrapper.Mouse(ReproductionEvent.mouseEventFromPde(evt));
      if (!ReproductionEvent.isReproductionEvent(evt)) {
        this.stopReproductionEvent = true;
      }
    }
  }

  def keyEvent(evt: KeyEvent) = {
    if (this.onTarget) {
      this.currentFrameEvents +=
        PdeEventWrapper.Key(ReproductionEvent.keyEventFromPde(evt));
      if (!ReproductionEvent.isReproductionEvent(evt)) {
        this.stopReproductionEvent = true;
      }
    }
  }
}
