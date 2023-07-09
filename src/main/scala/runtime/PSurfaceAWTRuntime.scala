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
    return new AnimationThreadRuntime();
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
          val sockPath = Path.of(System.getProperty("seekprog.sock"));
          val sockAddr = UnixDomainSocketAddress.of(sockPath);
          val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
          socketChannel.connect(sockAddr);

          sketch.frameRate(1e+9f + 1);
          val handlePre = new HandlePre(
            sketch,
            RuntimeMain.targetFrameCount,
            socketChannel,
            RuntimeMain.events
          );
          sketch.registerMethod("pre", handlePre);
          sketch.registerMethod("mouseEvent", handlePre);
          sketch.registerMethod("keyEvent", handlePre);
        }
        callDraw();
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

      sketch.dispose();

      if (sketch.exitCalled) {
        sketch.exitActual();
      }
    }
  }

}
