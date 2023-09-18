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
import processing.app.RunnerListenerEdtAdapter

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

  class SlaveVm(
      val vmManager: VmManager,
      val buildId: Int,
      var pdeEventCount: Int
  ) {
    var frameCount = Int.MaxValue;
  }

  class MasterVm(val vmManager: VmManager, val slaves: MMap[Int, SlaveVm]) {}

  enum Task {
    case TCmd(cmd: Cmd)
    case TMasterEvent(masterVm: MasterVm, event: VmManager.Event)
    case TSlaveEvent(slaveVm: SlaveVm, event: VmManager.Event)
  }
  export Task._

}

class EditorManager(val editor: JavaEditor) {
  import EditorManager._

  val taskQueue = new LinkedTransferQueue[Task]();
  var eventListeners = List[Event => Unit]();

  var frameCount = 0;
  var maxFrameCount = 0;
  val pdeEvents = Buffer[List[PdeEventWrapper]]();
  var running = false;
  var currentBuild: Build = null;
  val builds = Buffer[Build]();
  var oMasterVm: Option[MasterVm] = None;
  val slaves = MSet[Int]();
  var isExit = false;
  var masterLocation: Option[java.awt.Point] = None;
  val slaveLocations: MMap[Int, java.awt.Point] = MMap();

  private def updateBuild() = {
    try {
      editor.prepareRun();
      val javaBuild = new JavaBuild(editor.getSketch());
      javaBuild.build(true);
      currentBuild = new Build(this.builds.length, javaBuild);

      this.builds += currentBuild;
      this.eventListeners.foreach(
        _(Event.CreatedBuild(currentBuild))
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
    assert(oMasterVm.isDefined);
    val masterVm = oMasterVm.get;
    val vmManager = masterVm.vmManager;
    val minFrameCount = masterVm.slaves.values
      .filter(!_.vmManager.isExited)
      .map(_.frameCount)
      .minOption
      .getOrElse(Int.MaxValue);

    vmManager.sendSlaveSync(
      VmManager.SlaveSyncCmd.LimitFrameCount(minFrameCount)
    )
  }

  private def temporaryLocationWith(
      location: Option[java.awt.Point]
  )(f: => Unit) = {
    val oldLocation = editor.getSketchLocation();
    try {
      location.foreach(editor.setSketchLocation(_));
      f;
    } finally {
      location.foreach(_ => editor.setSketchLocation(oldLocation));
    }
  }

  private def startVm() = {
    assert(oMasterVm.isEmpty);
    editor.statusEmpty();

    val slaveVms = MMap[Int, SlaveVm](
      slaves.toSeq.map(id => (id -> createSlaveVm(id))): _*
    );
    val masterVmManager = new VmManager(
      javaBuild = currentBuild.javaBuild,
      slaveMode = false,
      runnerListener = new RunnerListenerEdtAdapter(editor),
      targetFrameCount = this.frameCount,
      defaultRunning = this.running,
      pdeEvents = this.pdeEvents.toList
    );
    val vmms = new MasterVm(masterVmManager, slaveVms);
    masterVmManager.listen { event =>
      taskQueue.put(
        TMasterEvent(
          vmms,
          event
        )
      )
    }
    oMasterVm = Some(vmms);

    val f1 = {
      val p = Promise[Unit]();
      blocking {
        temporaryLocationWith(masterLocation) {
          masterVmManager.start(p)
        }
      }
      p.future
    };
    // 失敗しても無視したい
    val f2 = Future.traverse(slaveVms.values.toSeq) {
      case slaveVm => {
        val p = Promise[Unit]();
        blocking {
          temporaryLocationWith(
            slaveLocations.get(slaveVm.buildId)
          ) {
            slaveVm.vmManager.start(p)
          }
        }
        p.future
      }
    };
    for {
      _ <- f1
      _ <- f2
      _ <- Future {
        updateSlaveVms();
      }
    } yield ()
  }

  private def createSlaveVm(buildId: Int) = {
    val slaveVm = new SlaveVm(
      new VmManager(
        javaBuild = builds(buildId).javaBuild,
        slaveMode = true,
        runnerListener = new RunnerListenerEdtAdapter(editor),
        targetFrameCount = this.frameCount,
        defaultRunning = true,
        pdeEvents = this.pdeEvents.toList
      ),
      buildId,
      this.frameCount
    );

    slaveVm.vmManager.listen { event =>
      taskQueue.put(
        TSlaveEvent(
          slaveVm,
          event
        )
      )
    };

    slaveVm
  }

  private def exitSlaveVm(slaveVm: SlaveVm) = {
    if (slaveVm.vmManager.isExited) {
      Future {
        slaveVm.vmManager.forceExit()
      }
    } else {
      val p = Promise[Unit]();
      slaveVm.vmManager.send(VmManager.Cmd.Exit(p));
      p.future
    }
  }

  private def exitVm() = {
    assert(oMasterVm.isDefined);
    val masterVm = oMasterVm.get;
    val vmManager = masterVm.vmManager;

    for {
      _ <-
        if (vmManager.isExited) {
          Future {
            vmManager.forceExit()
          }
        } else {
          val p = Promise[Unit]();
          vmManager.send(VmManager.Cmd.Exit(p));
          p.future
        }

      _ <- Future.traverse(masterVm.slaves.toSeq) { case (_, slaveVm) =>
        exitSlaveVm(slaveVm);
      }
      _ <- Future {
        oMasterVm = None;
      }
    } yield ()
  }

  def start() = {
    new Thread(() => {
      while (!isExit) {
        val task = taskQueue.take();
        task match {
          case TCmd(cmd)                 => processCmd(cmd)
          case TMasterEvent(vmms, event) => processMasterEvent(vmms, event)
          case TSlaveEvent(slaveVm, event) => {
            processSlaveEvent(slaveVm, event)
          }

        }

      }

      ()
    }).start();
  }

  private def processCmd(cmd: Cmd) = {
    cmd match {
      case Cmd.ReloadSketch(done) => {
        oMasterVm match {
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
      case Cmd.UpdateLocation(frameCount, done) => {
        oMasterVm match {
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
      case Cmd.StartSketch(done) => {
        oMasterVm match {
          case Some(masterVm) if masterVm.vmManager.isExited => {
            Await.ready(
              exitVm(),
              Duration.Inf
            )
          }
          case _ => {}
        }

        oMasterVm match {
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
      case Cmd.PauseSketch(done) => {
        oMasterVm match {
          case Some(masterVm) => {
            Await.ready(
              {
                masterVm.vmManager.send(
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
      case Cmd.ResumeSketch(done) => {
        oMasterVm match {
          case Some(masterVm) => {
            Await.ready(
              {
                masterVm.vmManager.send(
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
      case Cmd.Exit(done) => {
        oMasterVm match {
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
      case Cmd.AddSlave(id, done) => {
        if (slaves.contains(id)) {
          done.failure(new Exception("slave is already added"));
        } else {
          slaves += id;
          oMasterVm match {
            case Some(masterVm) => {
              val slaveVm = createSlaveVm(currentBuild.id);
              masterVm.slaves += (id -> slaveVm);

              Await.ready(
                done
                  .completeWith({
                    val p = Promise[Unit]();
                    blocking {
                      slaveVm.vmManager.start(p)
                    }
                    p.future
                  })
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
      case Cmd.RemoveSlave(id, done) => {
        if (!slaves.contains(id)) {
          done.failure(new Exception("slave is not added"));
        } else {
          slaves -= id;
          oMasterVm match {
            case Some(masterVm) => {
              Await.ready(
                done
                  .completeWith(for {
                    _ <- Future {
                      assert(masterVm.slaves.contains(id));
                    }
                    _ <- exitSlaveVm(masterVm.slaves(id))
                    _ <- Future {
                      masterVm.slaves -= id;
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

  private def processMasterEvent(vmms: MasterVm, event: VmManager.Event) = {
    event match {
      case VmManager.Event
            .UpdateLocation(frameCount, trimMax, events, windowX, windowY) => {
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
        for ((_, slaveVm) <- vmms.slaves) {
          slaveVm.vmManager.sendSlaveSync(
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
            Event.UpdateLocation(
              frameCount,
              this.maxFrameCount
            )
          )
        )

        this.masterLocation = Some(new java.awt.Point(windowX, windowY));
      }
      case VmManager.Event.Stopped() => {
        this.running = false;
        this.eventListeners.foreach(_(Event.Stopped()))
      }
    }
  }

  private def processSlaveEvent(slaveVm: SlaveVm, event: VmManager.Event) = {
    event match {
      case VmManager.Event.UpdateLocation(
            frameCount,
            trimMax,
            events,
            windowX,
            windowY
          ) => {
        slaveVm.frameCount = frameCount;
        updateSlaveVms();
        this.slaveLocations(slaveVm.buildId) =
          new java.awt.Point(windowX, windowY);
      }
      case VmManager.Event.Stopped() => {}
    }
  }

  def send(cmd: Cmd) = {
    taskQueue.put(TCmd(cmd));
  }

  def listen(listener: Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
