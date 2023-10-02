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
import seekprog.shared.RuntimeCmd
import seekprog.shared.RuntimeEvent
import seekprog.shared.FrameState
import seekprog.shared.InitParams

object RuntimeMain {
  var targetFrameCount = 0;
  val frameStates: Buffer[FrameState] = Buffer();
  private var sc: SocketChannel = null;
  var sketchHandler: SketchHandler = null;
  var paused = false;
  var notTriggerPausedEvent = false;
  val resumeQueue = new LinkedTransferQueue[Unit]();
  val runtimeEventQueue = new LinkedTransferQueue[RuntimeEvent]();
  var surface: PSurfaceAWTRuntime = null;
  var slaveMode = false;
  val addedFrameStatesQueue = new LinkedTransferQueue[List[FrameState]]();
  var frameCountLimit = Int.MaxValue;
  var isDebug = false;

  def init(
      sketch: PApplet,
      paramsJson: String
  ) = {
    val params = decode[InitParams](paramsJson).right.get;
    this.paused = params.initPaused;
    this.slaveMode = params.slaveMode;
    this.notTriggerPausedEvent = params.initPaused;
    this.isDebug = params.isDebug;
    val renderer = classOf[PGraphicsJava2DRuntime].getName();
    Class.forName(renderer);
    {
      val field = classOf[PApplet].getDeclaredField("renderer");
      field.setAccessible(true);
      field.set(sketch, renderer);
    }

    this.targetFrameCount = params.targetFrameCount;
    this.frameStates ++= params.frameStates;
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
          case RuntimeCmd.AddedFrameStates(events) => {
            addedFrameStatesQueue.put(events);
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
