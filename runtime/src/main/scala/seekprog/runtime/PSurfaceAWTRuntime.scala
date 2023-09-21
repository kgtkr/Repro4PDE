package seekprog.runtime;

import processing.awt.PSurfaceAWT
import processing.core.PGraphics;
import scala.jdk.CollectionConverters._
import java.awt.Canvas
import java.awt.event.{
  MouseListener,
  MouseMotionListener,
  MouseWheelListener,
  KeyListener
}
import seekprog.shared.RuntimeEvent

class PSurfaceAWTRuntime(graphics: PGraphics) extends PSurfaceAWT(graphics) {
  var mouseListeners: Array[MouseListener] = null;
  var mouseMotionListeners: Array[MouseMotionListener] = null;
  var mouseWheelListeners: Array[MouseWheelListener] = null;
  var keyListeners: Array[KeyListener] = null;

  if (RuntimeMain.surface == null) {
    RuntimeMain.surface = this;
  }

  def disableEvent() = {
    val canvas = PSurfaceAWTRuntime.this.getNative().asInstanceOf[Canvas];

    mouseListeners = canvas.getMouseListeners();
    mouseMotionListeners = canvas.getMouseMotionListeners();
    mouseWheelListeners = canvas.getMouseWheelListeners();
    keyListeners = canvas.getKeyListeners();

    for (listener <- mouseListeners) {
      canvas.removeMouseListener(listener);
    }

    for (listener <- mouseMotionListeners) {
      canvas.removeMouseMotionListener(listener);
    }

    for (listener <- mouseWheelListeners) {
      canvas.removeMouseWheelListener(listener);
    }

    for (listener <- keyListeners) {
      canvas.removeKeyListener(listener);
    }
  }

  def enableEvent() = {
    val canvas = PSurfaceAWTRuntime.this.getNative().asInstanceOf[Canvas];

    for (listener <- mouseListeners) {
      canvas.addMouseListener(listener);
    }

    for (listener <- mouseMotionListeners) {
      canvas.addMouseMotionListener(listener);
    }

    for (listener <- mouseWheelListeners) {
      canvas.addMouseWheelListener(listener);
    }

    for (listener <- keyListeners) {
      canvas.addKeyListener(listener);
    }
  }

  override def createThread(): Thread = {
    return new AnimationThreadRuntime {
      override def callDraw(): Unit = {
        sketch.handleDraw();
        if (RuntimeMain.sketchHandler.onTarget) {
          render();
        }
      }
    };
  }

  class AnimationThreadRuntime extends AnimationThread {

    override def run(): Unit = {
      var beforeTime = System.nanoTime();
      var overSleepTime = 0L;

      var noDelays = 0;
      val NO_DELAYS_PER_YIELD = 15;
      sketch.start();
      while ((Thread.currentThread() eq thread) && !sketch.finished) {
        checkPause();
        callDraw();
        if (PSurfaceAWTRuntime.this.frameRateTarget != 60) {
          throw new RuntimeException(
            "Seekprog only supports 60fps"
          );
        }
        if (sketch.finished && !sketch.exitCalled) {
          throw new RuntimeException(
            "Seekprog must implement draw()"
          );
        }
        if (!sketch.isLooping) {
          throw new RuntimeException(
            "Seekprog does not support noLoop()"
          );
        }

        if (RuntimeMain.sketchHandler.onTarget) {
          if (RuntimeMain.paused) {
            if (RuntimeMain.notTriggerPausedEvent) {
              RuntimeMain.notTriggerPausedEvent = false;
            } else {
              RuntimeMain.runtimeEventQueue.add(
                RuntimeEvent.OnPaused
              );
            }

            RuntimeMain.resumeQueue.take();
            RuntimeMain.runtimeEventQueue.add(
              RuntimeEvent.OnResumed
            );
          }
          while (
            RuntimeMain.slaveMode && RuntimeMain.events.length <= sketch.frameCount
          ) {
            RuntimeMain.events ++= RuntimeMain.addedEventsQueue.take();
          }

          val delayThreshold = 12;
          val addSleepTime =
            if (
              sketch.frameCount - delayThreshold >= RuntimeMain.frameCountLimit
            ) {
              val diff =
                (sketch.frameCount - delayThreshold - RuntimeMain.frameCountLimit).toLong;
              // 10フレームかけて均一化する計算
              diff * 1000 * 1000000L / 60 / 10
            } else {
              0
            }

          if (addSleepTime > 0) {
            RuntimeMain.log("addSleepTime: " + addSleepTime / 1000000.0 + "ms");
          }

          val afterTime = System.nanoTime();
          val timeDiff = afterTime - beforeTime;
          val sleepTime =
            (frameRatePeriod + addSleepTime - timeDiff) - overSleepTime;

          if (sleepTime > 0) { // some time left in this cycle
            try {
              Thread.sleep(sleepTime / 1000000L, (sleepTime % 1000000L).toInt);
              noDelays = 0; // Got some sleep, not delaying anymore
            } catch {
              case e: InterruptedException => {}
            }

            overSleepTime = (System.nanoTime() - afterTime) - sleepTime;

          } else {
            overSleepTime = 0L;
            noDelays += 1;

            if (noDelays > NO_DELAYS_PER_YIELD) {
              Thread.`yield`();
              noDelays = 0;
            }
          }

          beforeTime = System.nanoTime();
        }

      }

      sketch.dispose();

      if (sketch.exitCalled) {
        sketch.exitActual();
      }
    }
  }

}
