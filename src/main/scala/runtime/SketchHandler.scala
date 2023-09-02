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
    reproductionEvents: Vector[List[EventWrapper]]
) {
  var onTarget = false;
  val currentFrameEvents = Buffer[EventWrapper]();
  val eventsBuf = Buffer[List[EventWrapper]]();
  var stopReproductionEvent = false;
  var startTime = 0L;

  def pre() = {
    if (this.applet.frameCount == 1) {
      this.startTime = System.nanoTime();
    }

    if (!this.onTarget && this.applet.frameCount >= this.targetFrameCount) {
      this.onTarget = true;
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
            case EventWrapper.Mouse(evt) => this.applet.postEvent(evt.toPde());
            case EventWrapper.Key(evt)   => this.applet.postEvent(evt.toPde());
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
        EventWrapper.Mouse(MouseEventWrapper.fromPde(evt));
      if (evt.getNative() ne ReproductionEvent) {
        this.stopReproductionEvent = true;
      }
    }
  }

  def keyEvent(evt: KeyEvent) = {
    if (this.onTarget) {
      this.currentFrameEvents +=
        EventWrapper.Key(KeyEventWrapper.fromPde(evt));
      if (evt.getNative() ne ReproductionEvent) {
        this.stopReproductionEvent = true;
      }
    }
  }
}
