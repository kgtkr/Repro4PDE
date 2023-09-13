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

object RuntimeMain {
  var targetFrameCount = 0;
  var events: Vector[List[PdeEventWrapper]] = Vector();
  private var socketChannel: SocketChannel = null;
  var sketchHandler: SketchHandler = null;
  var paused = false;
  var notTriggerPausedEvent = false;
  val resumeQueue = new LinkedTransferQueue[Unit]();
  val runtimeEventQueue = new LinkedTransferQueue[RuntimeEvent]();
  var surface: PSurfaceAWTRuntime = null;

  def init(
      sketch: PApplet,
      targetFrameCount: Int,
      events: String,
      initPaused: Boolean
  ) = {
    this.paused = initPaused;
    this.notTriggerPausedEvent = initPaused;
    val renderer = classOf[PGraphicsJava2DRuntime].getName();
    Class.forName(renderer);
    {
      val field = classOf[PApplet].getDeclaredField("renderer");
      field.setAccessible(true);
      field.set(sketch, renderer);
    }

    this.targetFrameCount = targetFrameCount;
    this.events = decode[List[List[PdeEventWrapper]]](events).right.get.toVector
    this.socketChannel = {
      val sockPath = Path.of(sketch.args(0));
      val sockAddr = UnixDomainSocketAddress.of(sockPath);
      val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
      socketChannel.connect(sockAddr);
      socketChannel
    };
    new Thread(() => {
      while (true) {
        val event = runtimeEventQueue.take();
        socketChannel.write(event.toBytes())
      }
    }).start();
    new Thread(() => {
      while (true) {
        val buf = ByteBuffer.allocate(1024);
        val sBuf = new StringBuffer();

        while (socketChannel.read(buf) != -1) {
          buf.flip();
          sBuf.append(StandardCharsets.UTF_8.decode(buf));
          if (sBuf.charAt(sBuf.length() - 1) == '\n') {
            RuntimeCmd.fromJSON(sBuf.toString()) match {
              case RuntimeCmd.Pause() => {
                paused = true;
              }
              case RuntimeCmd.Resume() => {
                paused = false;
                resumeQueue.put(());
              }
            }
            sBuf.setLength(0);
          }

          buf.clear()
        }
      }
    }).start();
    this.sketchHandler = new SketchHandler(
      sketch,
      RuntimeMain.targetFrameCount,
      RuntimeMain.events
    );

    sketch.registerMethod("pre", sketchHandler);
    sketch.registerMethod("mouseEvent", sketchHandler);
    sketch.registerMethod("keyEvent", sketchHandler);
  }
}

class RuntimeMain {}
