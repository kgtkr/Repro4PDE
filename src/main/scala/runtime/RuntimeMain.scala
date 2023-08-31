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

object RuntimeMain {
  var targetFrameCount = 0;
  var events: Vector[List[EventWrapper]] = Vector();
  var socketChannel: SocketChannel = null;
  var sketchHandler: SketchHandler = null;

  def run(sketch: PApplet, targetFrameCount: Int, events: String) = {
    val renderer = classOf[PGraphicsJava2DRuntime].getName();
    Class.forName(renderer);
    {
      val field = classOf[PApplet].getDeclaredField("renderer");
      field.setAccessible(true);
      field.set(sketch, renderer);
    }

    this.targetFrameCount = targetFrameCount;
    this.events = decode[List[List[EventWrapper]]](events).right.get.toVector
    this.socketChannel = {
      val sockPath = Path.of(sketch.args(0));
      val sockAddr = UnixDomainSocketAddress.of(sockPath);
      val socketChannel = SocketChannel.open(StandardProtocolFamily.UNIX);
      socketChannel.connect(sockAddr);
      socketChannel
    };
    this.sketchHandler = new SketchHandler(
      sketch,
      RuntimeMain.targetFrameCount,
      socketChannel,
      RuntimeMain.events
    );
  }
}

class RuntimeMain {}
