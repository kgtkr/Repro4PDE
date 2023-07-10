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

class PSurfaceAWTRuntime(graphics: PGraphics) extends PSurfaceAWT(graphics) {
  override def createThread(): Thread = {
    return new AnimationThreadRuntime {
      override def callDraw(): Unit = {
        sketch.handleDraw();
        if (RuntimeMain.handlePre.onTarget) {
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
        if (sketch.frameCount == 0) {
          sketch.registerMethod("pre", RuntimeMain.handlePre);
          sketch.registerMethod("mouseEvent", RuntimeMain.handlePre);
          sketch.registerMethod("keyEvent", RuntimeMain.handlePre);
        }
        callDraw();
        if (RuntimeMain.handlePre.onTarget) {
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
