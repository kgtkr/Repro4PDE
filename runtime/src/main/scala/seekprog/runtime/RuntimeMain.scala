package seekprog.runtime;

import processing.core.PApplet;
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import java.nio.file.Path
import java.net.UnixDomainSocketAddress
import scala.jdk.CollectionConverters._
import scala.collection.mutable.Buffer
import io.circe._, io.circe.parser._
import java.util.concurrent.LinkedTransferQueue
import java.nio.charset.StandardCharsets
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels
import seekprog.shared.PdeEventWrapper
import seekprog.shared.RuntimeCmd
import seekprog.shared.RuntimeEvent

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
  var isDebug = false;

  def init(
      sketch: PApplet,
      targetFrameCount: Int,
      events: String,
      initPaused: Boolean,
      slaveMode: Boolean,
      isDebug: Boolean
  ) = {
    this.paused = initPaused;
    this.slaveMode = slaveMode;
    this.notTriggerPausedEvent = initPaused;
    this.isDebug = isDebug;
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

  def log(msg: String) = {
    if (isDebug) {
      println(s"[INFO] runtime: $msg");
    }
  }
}

class RuntimeMain {}