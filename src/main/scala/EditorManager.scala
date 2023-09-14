package net.kgtkr.seekprog;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Map;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.PathSearchingVirtualMachine;
import com.sun.jdi.StackFrame;
import com.sun.jdi.VMDisconnectedException;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.Connector;
import com.sun.jdi.connect.LaunchingConnector;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.Event;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import com.sun.jdi.event.MethodEntryEvent;
import com.sun.jdi.event.MethodExitEvent;
import com.sun.jdi.event.StepEvent;
import com.sun.jdi.event.VMDeathEvent;
import com.sun.jdi.event.VMDisconnectEvent;
import com.sun.jdi.request.BreakpointRequest;
import com.sun.jdi.request.ClassPrepareRequest;
import com.sun.jdi.request.EventRequestManager;
import com.sun.jdi.request.MethodEntryRequest;
import processing.mode.java.Commander;
import java.io.File;
import scala.jdk.CollectionConverters._
import processing.app.Util
import processing.app.Base
import processing.app.Platform
import processing.app.Preferences
import processing.mode.java.JavaMode
import processing.app.contrib.ModeContribution
import processing.app.Sketch
import processing.mode.java.JavaBuild
import java.util.concurrent.LinkedTransferQueue
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.net.StandardProtocolFamily
import java.nio.file.Files
import java.nio.file.Path
import net.kgtkr.seekprog.runtime.PdeEventWrapper
import scala.collection.mutable.Buffer
import processing.mode.java.JavaEditor
import scala.concurrent.Promise

enum VmExitReason {
  case Reload;
  case UpdateLocation;
  case Exit;
  case Unexpected;
}

enum EditorManagerCmd {
  val done: Promise[Unit];

  case ReloadSketch(done: Promise[Unit])
  case UpdateLocation(
      frameCount: Int,
      done: Promise[Unit]
  )
  case StartSketch(done: Promise[Unit])
  case PauseSketch(done: Promise[Unit])
  case ResumeSketch(done: Promise[Unit])
  case Exit(done: Promise[Unit])
}

enum EditorManagerEvent {
  case UpdateLocation(frameCount: Int, max: Int);
  case Stopped();
}

class EditorManager(val editor: JavaEditor) {
  val cmdQueue = new LinkedTransferQueue[EditorManagerCmd]();
  var eventListeners = List[EditorManagerEvent => Unit]();

  var frameCount = 0;
  var maxFrameCount = 0;
  val pdeEvents = Buffer[List[PdeEventWrapper]]();
  var progressCmd: Option[EditorManagerCmd] = None;
  var running = false;
  var build: JavaBuild = null;
  var lastVmExitReason = VmExitReason.Reload;
  var vmManager: Option[VmManager] = None;

  def run() = {
    new Thread(() => {
      while (lastVmExitReason != VmExitReason.Exit) {
        assert(progressCmd.isEmpty);
        val cmd = cmdQueue.take();
        progressCmd = Some(cmd);
        cmd match {
          case EditorManagerCmd.ReloadSketch(done)               => {
          }
          case EditorManagerCmd.UpdateLocation(frameCount, done) => {}
          case EditorManagerCmd.StartSketch(done) => {
            running = true;
            editor.statusEmpty();
            editor.activateRun();
            Iterator
              .continually({
                try {
                  if (lastVmExitReason != VmExitReason.UpdateLocation) {
                    editor.prepareRun();
                    val build = new JavaBuild(editor.getSketch());
                    build.build(true);
                    this.build = build;
                  }

                  val vm = new VmManager(this);
                  lastVmExitReason = vm.run();
                } catch {
                  case e: Exception => {
                    e.printStackTrace();
                    editor.statusError(e);
                    lastVmExitReason = VmExitReason.Unexpected;
                  }
                } finally {
                  editor.deactivateRun();
                }

                lastVmExitReason
              })
              .takeWhile(reason =>
                reason == VmExitReason.Reload || reason == VmExitReason.UpdateLocation
              )
              .toList
            running = false;
            if (lastVmExitReason == VmExitReason.Unexpected) {
              progressCmd.foreach(
                _.done.failure(new Exception("unexpected vm exit"))
              );
              progressCmd = None;
              this.eventListeners.foreach(
                _(
                  EditorManagerEvent.Stopped()
                )
              )
            }
          }
          case EditorManagerCmd.PauseSketch(done) => {},
          case EditorManagerCmd.ResumeSketch(done) => {},
          case EditorManagerCmd.Exit(done) => {
            vmManager match {
              case Some(vmManager) => {}
              case None => {
                running = false;
                done.success(());
                lastVmExitReason = VmExitReason.Exit;
                progressCmd = None;
              }
            }
          }
        }
      }

      ()
    }).start();
  }

  def listen(listener: EditorManagerEvent => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
