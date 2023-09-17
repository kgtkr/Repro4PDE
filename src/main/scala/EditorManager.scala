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
import scala.collection.mutable.Set as MSet

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
    case AddSlave(id: Int, done: Promise[Unit])
    case RemoveSlave(id: Int, done: Promise[Unit])
  }

  enum Event {
    case UpdateLocation(frameCount: Int, max: Int);
    case Stopped();
    case CreatedBuild(build: Build);
  }

  class SlaveVm(val vm: VmManager, var pdeEventCount: Int) {
    var frameCount = Int.MaxValue;
  }

  class VmManagers(var master: VmManager, val slaves: MMap[Int, SlaveVm]) {}
}

class EditorManager(val editor: JavaEditor) {
  val cmdQueue = new LinkedTransferQueue[EditorManager.Cmd]();
  var eventListeners = List[EditorManager.Event => Unit]();

  var frameCount = 0;
  var maxFrameCount = 0;
  val pdeEvents = Buffer[List[PdeEventWrapper]]();
  var running = false;
  var currentBuild: Build = null;
  val builds = Buffer[Build]();
  var vmManagers: Option[EditorManager.VmManagers] = None;
  val slaves = MSet[Int]();

  private def updateBuild() = {
    try {
      editor.prepareRun();
      val javaBuild = new JavaBuild(editor.getSketch());
      javaBuild.build(true);
      currentBuild = new Build(this.builds.length, javaBuild);

      this.builds += currentBuild;
      this.eventListeners.foreach(
        _(EditorManager.Event.CreatedBuild(currentBuild))
      );
    } catch {
      case e: Exception => {
        e.printStackTrace();
        editor.statusError(e);
        throw e;
      }
    }
  }

  private def updateSlaveVms() = {
    assert(vmManagers.isDefined);
    val oldVmManagers = vmManagers.get;
    val oldVmManager = oldVmManagers.master;
    val minFrameCount = oldVmManagers.slaves.values
      .map(_.frameCount)
      .minOption
      .getOrElse(Int.MaxValue);

    oldVmManager.slaveSyncCmdQueue.put(
      VmManager.SlaveSyncCmd.LimitFrameCount(minFrameCount)
    )
  }

  private def startVm() = {
    assert(vmManagers.isEmpty);
    editor.statusEmpty();

    for {
      slaveVms <- Future
        .traverse(slaves.toSeq) { id =>
          startSlaveVm(id).map(
            (id -> _)
          )
        }
        .map(MMap(_: _*))
      newVmManager <- {
        val p = Promise[Unit]();
        val newVmManager = new VmManager(this);
        blocking {
          newVmManager.run(p)
        }
        newVmManager.listen {
          // TODO: スレッドセーフじゃない
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
            for ((_, slaveVm) <- slaveVms) {
              slaveVm.vm.slaveSyncCmdQueue.put(
                VmManager.SlaveSyncCmd.AddedEvents(
                  pdeEvents
                    .take(
                      frameCount
                    )
                    .drop(slaveVm.pdeEventCount)
                    .toList
                )
              );
              slaveVm.pdeEventCount = frameCount;
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
        vmManagers = Some(
          new EditorManager.VmManagers(newVmManager, slaveVms)
        )
        updateSlaveVms();
      }
    } yield ()
  }

  private def startSlaveVm(buildId: Int) = {
    // 起動しなくても実行は続けたいのでエラーをうまく無視するべき
    for {
      newVmManager <- {
        val p = Promise[Unit]();
        val newVmManager = new EditorManager.SlaveVm(
          new VmManager(this, Some(buildId)),
          this.frameCount
        );
        blocking {
          newVmManager.vm.run(p)
        }
        newVmManager.vm.listen {
          case VmManager.Event.UpdateLocation(frameCount, trimMax, events) => {
            newVmManager.frameCount = frameCount;
            updateSlaveVms();
          }
          case VmManager.Event.Stopped() => {}
        }
        p.future.map(_ => newVmManager)
      }
    } yield newVmManager
  }

  private def exitVm() = {
    assert(vmManagers.isDefined);
    val oldVmManagers = vmManagers.get;
    val oldVmManager = oldVmManagers.master;

    for {
      _ <- {
        val p = Promise[Unit]();
        oldVmManager.cmdQueue.put(VmManager.Cmd.Exit(p));
        p.future
      }
      _ <- Future.traverse(oldVmManagers.slaves.toSeq) {
        case (id, slaveVm) => {
          val p = Promise[Unit]();
          slaveVm.vm.cmdQueue.put(VmManager.Cmd.Exit(p));
          p.future
        }
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
          case EditorManager.Cmd.AddSlave(id, done) => {
            if (slaves.contains(id)) {
              done.failure(new Exception("slave is already added"));
            } else {
              slaves += id;
              vmManagers match {
                case Some(vmManagers) => {
                  Await.ready(
                    done
                      .completeWith(for {
                        vm <- startSlaveVm(id)
                        _ <- Future {
                          assert(!vmManagers.slaves.contains(id));
                          vmManagers.slaves += (id -> vm);
                        }
                      } yield ())
                      .future,
                    Duration.Inf
                  )

                  updateSlaveVms();
                }
                case None => {
                  done.success(());
                }
              }
            }

          }
          case EditorManager.Cmd.RemoveSlave(id, done) => {
            if (!slaves.contains(id)) {
              done.failure(new Exception("slave is not added"));
            } else {
              slaves -= id;
              vmManagers match {
                case Some(vmManagers) => {
                  Await.ready(
                    done
                      .completeWith(for {
                        _ <- Future {
                          assert(vmManagers.slaves.contains(id));
                        }
                        _ <- {
                          val done = Promise[Unit]();
                          vmManagers
                            .slaves(id)
                            .vm
                            .cmdQueue
                            .put(
                              VmManager.Cmd.Exit(done)
                            );
                          done.future
                        }
                        _ <- Future {
                          vmManagers.slaves -= id;
                          updateSlaveVms();
                        }
                      } yield ())
                      .future,
                    Duration.Inf
                  )
                }
                case None => {
                  done.success(());
                }
              }
            }
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
