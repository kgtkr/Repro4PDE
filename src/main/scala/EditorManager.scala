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
import scala.collection.mutable.Map as MMap

object EditorManager {
  enum Cmd {
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

  enum Event {
    case UpdateLocation(frameCount: Int, max: Int);
    case Stopped();
    case AddedPrevBuild(build: Build);
  }

  class VmManagers(var master: VmManager, val slaves: MMap[Int, VmManager]) {}
}

class EditorManager(val editor: JavaEditor) {
  val cmdQueue = new LinkedTransferQueue[EditorManager.Cmd]();
  var eventListeners = List[EditorManager.Event => Unit]();

  var frameCount = 0;
  var maxFrameCount = 0;
  val pdeEvents = Buffer[List[PdeEventWrapper]]();
  var running = false;
  var build: Build = null;
  val prevBuilds = Buffer[Build]();
  var vmManagers: Option[EditorManager.VmManagers] = None;

  private def updateBuild() = {
    try {
      editor.prepareRun();
      val javaBuild = new JavaBuild(editor.getSketch());
      javaBuild.build(true);

      if (build != null) {
        this.prevBuilds += build;
        this.eventListeners.foreach(
          _(EditorManager.Event.AddedPrevBuild(build))
        );
      }
      build = new Build(this.prevBuilds.length, javaBuild);
    } catch {
      case e: Exception => {
        e.printStackTrace();
        editor.statusError(e);
        throw e;
      }
    }
  }

  private def startVm() = {
    assert(vmManagers.isEmpty);
    editor.statusEmpty();

    for {
      newVmManager <- {
        val p = Promise[Unit]();
        val newVmManager = new VmManager(this);
        blocking {
          newVmManager.run(p)
        }
        newVmManager.listen {
          case VmManager.Event.UpdateLocation(frameCount, trimMax, events) => {
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
                EditorManager.Event.UpdateLocation(
                  frameCount,
                  this.maxFrameCount
                )
              )
            )
          }
          case VmManager.Event.Stopped() => {
            this.running = false;
            this.eventListeners.foreach(_(EditorManager.Event.Stopped()))
          }
        }
        p.future.map(_ => newVmManager)
      }
      _ <- Future {
        vmManagers = Some(new EditorManager.VmManagers(newVmManager, MMap()))
      }
    } yield ()
  }

  private def exitVm() = {
    assert(vmManagers.isDefined);
    val oldVmManager = vmManagers.get.master;

    for {
      _ <- {
        val p = Promise[Unit]();
        oldVmManager.cmdQueue.put(VmManager.Cmd.Exit(p));
        p.future
      }
      _ <- Future {
        vmManagers = None;
      }
    } yield ()
  }

  def run() = {
    new Thread(() => {
      var isExit = false;
      while (!isExit) {
        val cmd = cmdQueue.take();
        cmd match {
          case EditorManager.Cmd.ReloadSketch(done) => {
            vmManagers match {
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
          case EditorManager.Cmd.UpdateLocation(frameCount, done) => {
            vmManagers match {
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
          case EditorManager.Cmd.StartSketch(done) => {
            vmManagers match {
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
          case EditorManager.Cmd.PauseSketch(done) => {
            vmManagers match {
              case Some(vmManagers) => {
                Await.ready(
                  {
                    vmManagers.master.cmdQueue.put(
                      VmManager.Cmd.PauseSketch(done)
                    )
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
          case EditorManager.Cmd.ResumeSketch(done) => {
            vmManagers match {
              case Some(vmManagers) => {
                Await.ready(
                  {
                    vmManagers.master.cmdQueue.put(
                      VmManager.Cmd.ResumeSketch(done)
                    )
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
          case EditorManager.Cmd.Exit(done) => {
            vmManagers match {
              case Some(_) => {
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

  def listen(listener: EditorManager.Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
