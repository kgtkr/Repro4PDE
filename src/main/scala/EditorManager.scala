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
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.blocking
import scala.concurrent.Future
import scala.concurrent.Await
import scala.concurrent.duration.Duration

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
  var running = false;
  var build: JavaBuild = null;
  var vmManager: Option[VmManager] = None;

  private def updateBuild() = {
    try {
      editor.prepareRun();
      val build = new JavaBuild(editor.getSketch());
      build.build(true);
      this.build = build;
    } catch {
      case e: Exception => {
        e.printStackTrace();
        editor.statusError(e);
        throw e;
      }
    }
  }

  private def startVm() = {
    assert(vmManager.isEmpty);
    editor.statusEmpty();

    for {
      newVmManager <- {
        val p = Promise[Unit]();
        val newVmManager = new VmManager(this);
        blocking {
          newVmManager.run(p)
        }
        newVmManager.listen {
          case VmManagerEvent.UpdateLocation(frameCount, trimMax, events) => {
            this.frameCount = frameCount;
            this.maxFrameCount = if (trimMax) {
              frameCount
            } else {
              Math.max(this.maxFrameCount, frameCount);
            };

            if (this.maxFrameCount < this.pdeEvents.length) {
              this.pdeEvents.trimEnd(
                this.pdeEvents.length - this.maxFrameCount
              );
            } else if (this.maxFrameCount > this.pdeEvents.length) {
              this.pdeEvents ++= Seq.fill(
                this.maxFrameCount - this.pdeEvents.length
              )(List());
            }
            for ((event, i) <- events.zipWithIndex) {
              this.pdeEvents(frameCount - events.length + i) = event;
            }
            this.eventListeners.foreach(
              _(
                EditorManagerEvent.UpdateLocation(
                  frameCount,
                  this.maxFrameCount
                )
              )
            )
          }
          case VmManagerEvent.Stopped() => {
            this.running = false;
            this.eventListeners.foreach(_(EditorManagerEvent.Stopped()))
          }
        }
        p.future.map(_ => newVmManager)
      }
      _ <- Future {
        vmManager = Some(newVmManager)
      }
    } yield ()
  }

  private def exitVm() = {
    assert(vmManager.isDefined);
    val oldVmManager = vmManager.get;

    for {
      _ <- {
        val p = Promise[Unit]();
        oldVmManager.cmdQueue.put(VmManagerCmd.Exit(p));
        p.future
      }
      _ <- Future {
        vmManager = None;
      }
    } yield ()
  }

  def run() = {
    new Thread(() => {
      var isExit = false;
      while (!isExit) {
        val cmd = cmdQueue.take();
        cmd match {
          case EditorManagerCmd.ReloadSketch(done) => {
            vmManager match {
              case Some(_) => {
                try {
                  this.updateBuild();
                  Await.ready(
                    done
                      .completeWith(for {
                        _ <- exitVm()
                        _ <- startVm()
                      } yield ())
                      .future,
                    Duration.Inf
                  )
                } catch {
                  case e: Exception => {
                    done.failure(e);
                  }
                }

              }
              case None => {
                done.failure(new Exception("vm is not running"));
              }
            }
          }
          case EditorManagerCmd.UpdateLocation(frameCount, done) => {
            vmManager match {
              case Some(_) => {
                Await.ready(
                  done
                    .completeWith(for {
                      _ <- exitVm()
                      _ <- Future {
                        this.frameCount = frameCount;
                      }
                      _ <- startVm()
                    } yield ())
                    .future,
                  Duration.Inf
                )
              }
              case None => {
                done.failure(new Exception("vm is not running"));
              }
            }
          }
          case EditorManagerCmd.StartSketch(done) => {
            vmManager match {
              case Some(_) => {
                done.failure(new Exception("vm is already running"));
              }
              case None => {
                try {
                  running = true;
                  this.updateBuild();
                  Await.ready(
                    done
                      .completeWith(startVm())
                      .future,
                    Duration.Inf
                  )
                } catch {
                  case e: Exception => {
                    done.failure(e);
                  }
                }
              }
            }
          }
          case EditorManagerCmd.PauseSketch(done) => {
            vmManager match {
              case Some(vmManager) => {
                Await.ready(
                  {
                    vmManager.cmdQueue.put(VmManagerCmd.PauseSketch(done))
                    done.future
                  },
                  Duration.Inf
                )
                this.running = false;
              }
              case None => {
                done.failure(new Exception("vm is not running"));
              }
            }
          }
          case EditorManagerCmd.ResumeSketch(done) => {
            vmManager match {
              case Some(vmManager) => {
                Await.ready(
                  {
                    vmManager.cmdQueue.put(VmManagerCmd.ResumeSketch(done))
                    done.future
                  },
                  Duration.Inf
                )
                this.running = false;
              }
              case None => {
                done.failure(new Exception("vm is not running"));
              }
            }
          }
          case EditorManagerCmd.Exit(done) => {
            vmManager match {
              case Some(vmManager) => {
                Await.ready(
                  done
                    .completeWith(exitVm())
                    .future,
                  Duration.Inf
                )
                running = false;
              }
              case None => {
                running = false;
                done.success(());
              }
            }

            isExit = true;
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
