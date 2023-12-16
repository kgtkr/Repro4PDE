package repro4pde.runtime;

import processing.core.PApplet;
import processing.event.MouseEvent
import processing.event.KeyEvent
import scala.collection.mutable.Buffer
import io.circe._, io.circe.generic.semiauto._
import scala.util.Try
import repro4pde.runtime.shared.PdeEventWrapper
import repro4pde.runtime.shared.RuntimeEvent
import repro4pde.runtime.shared.FrameState
import scala.collection.mutable.{Map => MMap}

class SketchHandler(
    applet: PApplet,
    targetFrameCount: Int
) {
  var onTarget = false;
  val currentFrameEvents = Buffer[PdeEventWrapper]();
  val frameStatesBuf = Buffer[FrameState]();
  var stopReproductionEvent = false;
  var startTime = 0L;
  // screenshot目的だけであれば enableDraw=true, enableRender=false になる
  var enableDraw = false;
  var enableRender = false;
  var screenshotCount = 0;
  // OnTarget以前のスクリーンショット
  val screenshotPaths = MMap[Int, String]();
  var enableScreenshot = false;
  var prevScreenshotFrameCount = 0;

  def pre() = {
    if (this.applet.frameCount == 0) {
      this.startTime = System.nanoTime();

      if (
        applet
          .sketchRenderer() != classOf[PGraphicsJava2DRuntime].getName()
      ) {
        throw new RuntimeException(
          "Repro4PDE not support renderer settings. size() must be two arguments."
        );
      }

      RuntimeMain.surface.disableEvent();
      this.applet.randomSeed(RuntimeMain.randomSeed);
    }

    if (!this.onTarget && this.applet.frameCount >= this.targetFrameCount) {
      this.onTarget = true;
      this.enableDraw = true;
      this.enableRender = true;
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
      // コンソールリセットのため(よりよい方法が欲しい)
      for (i <- 0 until 100) {
        println();
      }
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent.OnTargetFrameCount(this.screenshotPaths.toMap)
      )
    }

    if (!this.stopReproductionEvent) {
      Try(RuntimeMain.frameStates(this.applet.frameCount)).toOption
        .foreach { frameState =>
          frameState.events.foreach {
            case PdeEventWrapper.Mouse(evt) =>
              this.applet.postEvent(ReproductionEvent.mouseEventToPde(evt));
            case PdeEventWrapper.Key(evt) =>
              this.applet.postEvent(ReproductionEvent.keyEventToPde(evt));
          };
        };
    }

    if (this.onTarget) {
      this.frameStatesBuf += FrameState(
        events = this.currentFrameEvents.toList
      )
      this.currentFrameEvents.clear();
    }

    if (!RuntimeMain.slaveMode && this.applet.frameCount > 0) {
      // 合計枚数を抑えるため時間が経つほど徐々にスクショ頻度を下げる
      val waitFrameCount =
        60 max (RuntimeMain.targetFrameCount / 10) max (this.applet.frameCount / 10);
      if (
        this.applet.frameCount >= this.prevScreenshotFrameCount + waitFrameCount
      ) {
        this.enableScreenshot = true;
        this.prevScreenshotFrameCount = this.applet.frameCount;
      } else {
        this.enableScreenshot = false;
      }
    }

    if (!this.onTarget) {
      // onTargetの1フレーム前も描画する
      if (this.applet.frameCount >= this.targetFrameCount - 1) {
        this.enableDraw = true;
        this.enableRender = true;
      } else if (!RuntimeMain.slaveMode) {
        this.enableDraw = this.enableScreenshot;
      }
    }

  }

  def post() = {
    val screenshotPath =
      if (this.enableScreenshot) {
        val path = RuntimeMain.screenshotsDir
          .resolve(
            this.screenshotCount.toString() + ".png"
          )
          .toString();
        this.applet.saveFrame(
          path
        );
        this.screenshotCount += 1;
        Some(path)
      } else {
        None
      }

    if (this.onTarget /*&& this.applet.frameCount % 10 == 0*/ ) {
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent
          .OnUpdateLocation(
            this.applet.frameCount,
            this.stopReproductionEvent,
            this.frameStatesBuf.toList,
            this.applet.windowX,
            this.applet.windowY,
            screenshotPath
          )
      )
      this.frameStatesBuf.clear();
    } else {
      screenshotPath.foreach { path =>
        this.screenshotPaths += (this.applet.frameCount -> path);
      }
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
