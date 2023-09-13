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

class SketchHandler(
    applet: PApplet,
    targetFrameCount: Int,
    reproductionEvents: Vector[List[PdeEventWrapper]]
) {
  var onTarget = false;
  val currentFrameEvents = Buffer[PdeEventWrapper]();
  val eventsBuf = Buffer[List[PdeEventWrapper]]();
  var stopReproductionEvent = false;
  var startTime = 0L;

  def pre() = {
    if (this.applet.frameCount == 1) {
      this.startTime = System.nanoTime();

      if (
        applet
          .sketchRenderer() != classOf[PGraphicsJava2DRuntime].getName()
      ) {
        throw new RuntimeException(
          "Seekprog not support renderer settings. size() must be two arguments."
        );
      }

      RuntimeMain.surface.disableEvent();
    }

    if (!this.onTarget && this.applet.frameCount >= this.targetFrameCount) {
      this.onTarget = true;
      RuntimeMain.surface.enableEvent();
      val endTime = System.nanoTime();
      val ms = (endTime - this.startTime) / 1000000.0;
      println(
        "onTarget: " + ms + "ms, " +
          "targetFrameCount: " + this.targetFrameCount + ", " +
          "frameRate: " + this.targetFrameCount / ms * 1000
      );
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent.OnTargetFrameCount
      )
    }

    if (!this.stopReproductionEvent) {
      Try(this.reproductionEvents(this.applet.frameCount - 1)).toOption
        .foreach {
          _.foreach {
            case PdeEventWrapper.Mouse(evt) =>
              this.applet.postEvent(evt.toPde());
            case PdeEventWrapper.Key(evt) => this.applet.postEvent(evt.toPde());
          };
        };
    }

    if (this.onTarget) {
      this.eventsBuf += this.currentFrameEvents.toList;
      this.currentFrameEvents.clear();
    }

    if (this.onTarget && this.applet.frameCount % 60 == 0) {
      RuntimeMain.runtimeEventQueue.add(
        RuntimeEvent
          .OnUpdateLocation(
            this.applet.frameCount,
            this.stopReproductionEvent,
            this.eventsBuf.toList
          )
      )
      this.eventsBuf.clear();
    }
  }

  def mouseEvent(evt: MouseEvent) = {
    if (this.onTarget) {
      this.currentFrameEvents +=
        PdeEventWrapper.Mouse(PdeMouseEventWrapper.fromPde(evt));
      if (evt.getNative() ne ReproductionEvent) {
        this.stopReproductionEvent = true;
      }
    }
  }

  def keyEvent(evt: KeyEvent) = {
    if (this.onTarget) {
      this.currentFrameEvents +=
        PdeEventWrapper.Key(PdeKeyEventWrapper.fromPde(evt));
      if (evt.getNative() ne ReproductionEvent) {
        this.stopReproductionEvent = true;
      }
    }
  }
}
