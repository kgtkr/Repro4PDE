package net.kgtkr.seekprog.runtime;

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
import java.util.concurrent.LinkedTransferQueue
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import processing.core.PConstants
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels

object RuntimeMain {
  var targetFrameCount = 0;
  val events: Buffer[List[PdeEventWrapper]] = Buffer();
  private var sc: SocketChannel = null;
  var sketchHandler: SketchHandler = null;
  var paused = false;
  var notTriggerPausedEvent = false;
  val resumeQueue = new LinkedTransferQueue[Unit]();
  val runtimeEventQueue = new LinkedTransferQueue[RuntimeEvent]();
  var surface: PSurfaceAWTRuntime = null;
  var slaveMode = false;
  val addedEventsQueue = new LinkedTransferQueue[List[List[PdeEventWrapper]]]();
  var frameCountLimit = Int.MaxValue;

  def init(
      sketch: PApplet,
      targetFrameCount: Int,
      events: String,
      initPaused: Boolean,
      slaveMode: Boolean
  ) = {
    this.paused = initPaused;
    this.slaveMode = slaveMode;
    this.notTriggerPausedEvent = initPaused;
    val renderer = classOf[PGraphicsJava2DRuntime].getName();
    Class.forName(renderer);
    {
      val field = classOf[PApplet].getDeclaredField("renderer");
      field.setAccessible(true);
      field.set(sketch, renderer);
    }

    this.targetFrameCount = targetFrameCount;
    this.events ++= decode[List[List[PdeEventWrapper]]](
      events
    ).right.get
    this.sc = {
      val sockPath = Path.of(sketch.args(0));
      val sockAddr = UnixDomainSocketAddress.of(sockPath);
      val sc = SocketChannel.open(StandardProtocolFamily.UNIX);
      sc.connect(sockAddr);
      sc
    };
    new Thread(() => {
      while (true) {
        val event = runtimeEventQueue.take();
        sc.write(event.toBytes())
      }
    }).start();
    new Thread(() => {
      val bs = new BufferedReader(
        new InputStreamReader(
          Channels.newInputStream(sc),
          StandardCharsets.UTF_8
        )
      );

      for (
        line <- Iterator
          .continually {
            bs.readLine()
          }
          .takeWhile(_ != null)
      ) {
        RuntimeCmd.fromJSON(line) match {
          case RuntimeCmd.Pause() => {
            paused = true;
          }
          case RuntimeCmd.Resume() => {
            paused = false;
            resumeQueue.put(());
          }
          case RuntimeCmd.AddedEvents(events) => {
            addedEventsQueue.put(events);
          }
          case RuntimeCmd.LimitFrameCount(frameCount) => {
            frameCountLimit = frameCount;
          }
        }
      }
    }).start();
    this.sketchHandler = new SketchHandler(
      sketch,
      RuntimeMain.targetFrameCount
    );

    sketch.registerMethod("pre", sketchHandler);
    sketch.registerMethod("mouseEvent", sketchHandler);
    sketch.registerMethod("keyEvent", sketchHandler);
  }
}

class RuntimeMain {}
