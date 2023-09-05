package net.kgtkr.seekprog.runtime;

import processing.awt.PSurfaceAWT
import processing.core.PGraphics
import processing.core.PApplet;
import processing.core.PGraphics;
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.nio.file.Path
import java.net.UnixDomainSocketAddress
import processing.event.MouseEvent
import processing.event.KeyEvent
import scala.jdk.CollectionConverters._
import scala.collection.mutable.Buffer
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import scala.util.Try
import processing.core.PConstants

class PSurfaceAWTRuntime(graphics: PGraphics) extends PSurfaceAWT(graphics) {
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
          val afterTime = System.nanoTime();
          val timeDiff = afterTime - beforeTime;
          val sleepTime = (frameRatePeriod - timeDiff) - overSleepTime;

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
