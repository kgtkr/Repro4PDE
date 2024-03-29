package repro4pde.app;

import java.io.InputStreamReader;
import java.util.Arrays;
import com.sun.jdi.ClassType;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import scala.jdk.CollectionConverters._
import processing.mode.java.JavaBuild
import java.util.concurrent.LinkedTransferQueue
import java.nio.channels.ServerSocketChannel
import java.io.BufferedReader
import java.nio.charset.StandardCharsets
import io.circe._, io.circe.syntax._
import processing.mode.java.runner.Runner
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.Path
import java.net.UnixDomainSocketAddress
import java.net.StandardProtocolFamily
import repro4pde.utils.ext._;
import scala.concurrent.Promise
import java.nio.channels.Channels
import scala.collection.mutable.Buffer
import processing.app.RunnerListener
import com.sun.jdi.VMDisconnectedException
import com.sun.jdi.event.VMDeathEvent
import repro4pde.runtime.shared.RuntimeCmd
import repro4pde.runtime.shared.RuntimeEvent
import repro4pde.runtime.shared.FrameState
import repro4pde.runtime.shared.InitParams
object VmManager {
  enum SlaveSyncCmd {
    case AddedEvents(frameStates: List[FrameState]);
    case LimitFrameCount(frameCount: Int);
  }

  enum Cmd {
    case StartSketch() // for internal
    case PauseSketch()
    case ResumeSketch()
    case Exit()

  }

  enum Event {
    case UpdateLocation(
        frameCount: Int,
        trimMax: Boolean,
        frameStates: List[FrameState],
        windowX: Int,
        windowY: Int
    );
    case Stopped();
    case AddedScreenshots(screenshotPaths: Map[Int, String]);
  }

  enum Task {
    case TVmEvent(eventSet: EventSet);
    case TCmd(cmd: Cmd, done: Promise[Unit]);
    case TSlaveSyncCmd(cmd: SlaveSyncCmd);
    case TRuntimeEvent(event: RuntimeEvent);
  }
  export Task._;

  enum ExitType {
    case Running;
    case ExitCmd;
    case Exception;
    case VmDeath;
  }

}

class VmManager(
    val javaBuild: JavaBuild,
    val slaveMode: Boolean,
    val runnerListener: RunnerListener,
    val targetFrameCount: Int,
    val defaultRunning: Boolean,
    val frameStates: List[FrameState],
    val randomSeed: Long
) {
  import VmManager._;

  var eventListeners = List[Event => Unit]();
  var progressCmd: Option[(Cmd, Promise[Unit])] = None;
  var running = false;
  val taskQueue = new LinkedTransferQueue[Task]();
  @volatile
  var vmForForceExit: Option[VirtualMachine] = None;
  @volatile
  var exited = false;

  def start(startDone: Promise[Unit]) = {
    var exitType = ExitType.Running;

    val runtimeDir = Files.createTempDirectory("repro4pde");
    runtimeDir.toFile().deleteOnExit();

    val sockPath = Path.of(runtimeDir.toString(), "repro4pde.sock");

    val sockAddr = UnixDomainSocketAddress.of(sockPath);
    val ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    ssc.bind(sockAddr);

    running = defaultRunning;
    val runner =
      new Runner(javaBuild, runnerListener);

    val vm =
      runner.debug(Array(runtimeDir.toString()));

    val classPrepareRequest =
      vm.eventRequestManager().createClassPrepareRequest();
    classPrepareRequest.addClassFilter(javaBuild.getSketchClassName());
    classPrepareRequest.enable();

    val exceptionRequest =
      vm.eventRequestManager().createExceptionRequest(null, false, true);
    exceptionRequest.enable();

    val runtimeCmdQueue = new LinkedTransferQueue[RuntimeCmd]();
    val threads = Buffer[Thread]();
    {
      val thread =
        new Thread(() => {
          val sc = ssc.accept();

          val runtimeCmdThread = new Thread(() => {
            for (
              cmd <- Iterator
                .continually({
                  try {
                    Some(runtimeCmdQueue.take())
                  } catch {
                    case e: InterruptedException => {
                      None
                    }
                  }
                })
                .mapWhile(identity)
            ) {
              sc.write(cmd.toBytes());
            }
          });
          runtimeCmdThread.start();

          val bs = new BufferedReader(
            new InputStreamReader(
              Channels.newInputStream(sc),
              StandardCharsets.UTF_8
            )
          );

          for (
            line <- Iterator
              .continually {
                try {
                  bs.readLine()
                } catch {
                  case e: ClosedByInterruptException => {
                    runtimeCmdThread.interrupt();
                    null
                  }
                }
              }
              .takeWhile(_ != null)
          ) {
            taskQueue.add(TRuntimeEvent(RuntimeEvent.fromJSON(line)));
          }
          ()
        });
      thread.start();
      threads += thread;
    };

    {
      val eventQueue = vm.eventQueue();
      val thread = new Thread(() => {
        try {
          while (true) {
            taskQueue.add(TVmEvent(eventQueue.remove()));
          }
        } catch {
          case _: VMDisconnectedException => {}
          case _: InterruptedException    => {}
        }

      });
      thread.start();
      threads += thread;
    };
    this.progressCmd = Some(Cmd.StartSketch(), startDone);

    new Thread(() => {
      try {
        while (exitType == ExitType.Running) {
          val task = taskQueue.take();
          task match {
            case TVmEvent(eventSet) => {
              for (evt <- eventSet.asScala) {
                Logger.log(evt.toString());
                evt match {
                  case evt: ClassPrepareEvent => {
                    val classType = evt.referenceType().asInstanceOf[ClassType];
                    if (classType.name() == javaBuild.getSketchClassName()) {
                      vm.eventRequestManager()
                        .createBreakpointRequest(
                          classType.methodsByName("settings").get(0).location()
                        )
                        .enable();
                    }
                  }
                  case evt: BreakpointEvent => {
                    val frame = evt.thread().frame(0);
                    val instance = frame.thisObject();

                    evt.location().method().name() match {
                      case "settings" => {
                        val ClassClassType = vm
                          .classesByName("java.lang.Class")
                          .get(0)
                          .asInstanceOf[ClassType];
                        val runtimeMainClassName =
                          "repro4pde.runtime.RuntimeMain";
                        ClassClassType.invokeMethod(
                          evt.thread(),
                          ClassClassType
                            .methodsByName(
                              "forName",
                              "(Ljava/lang/String;)Ljava/lang/Class;"
                            )
                            .get(0),
                          Arrays.asList(
                            vm.mirrorOf(
                              runtimeMainClassName
                            )
                          ),
                          0
                        );

                        val RuntimeMainClassType = vm
                          .classesByName(
                            runtimeMainClassName
                          )
                          .get(0)
                          .asInstanceOf[ClassType];

                        RuntimeMainClassType.invokeMethod(
                          evt.thread(),
                          RuntimeMainClassType
                            .methodsByName("init")
                            .get(0),
                          Arrays.asList(
                            instance,
                            vm.mirrorOf(
                              InitParams(
                                targetFrameCount = targetFrameCount,
                                frameStates =
                                  if (slaveMode)
                                    frameStates.toList.take(
                                      targetFrameCount + 1
                                    )
                                  else frameStates.toList,
                                initPaused = !running,
                                slaveMode = slaveMode,
                                isDebug = Repro4PDEApp.isDebug,
                                randomSeed = randomSeed
                              ).asJson.noSpaces
                            )
                          ),
                          0
                        );
                        evt.request().disable();
                      }
                      case _ =>
                        throw new RuntimeException(
                          "Unreachable"
                        )
                    }

                  }
                  case evt: ExceptionEvent => {
                    runner.exceptionEvent(evt);
                    exitType = ExitType.Exception;
                  }
                  case evt: VMDeathEvent => {
                    exitType = ExitType.VmDeath;
                  }
                  case _ => {}
                }
              }

              if (
                exitType != ExitType.ExitCmd || exitType != ExitType.VmDeath
              ) {
                vm.resume();
              }
            }
            case TCmd(cmd, done) => {
              if (progressCmd.isEmpty) {
                progressCmd = Some((cmd, done));

                cmd match {
                  case Cmd.StartSketch() => {
                    progressCmd = None;
                    done.failure(new Exception("already started"));
                  }
                  case Cmd.PauseSketch() => {
                    if (!running) {
                      progressCmd = None;
                      done.failure(new Exception("already paused"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Pause());
                      running = false;
                    }

                  }
                  case Cmd.ResumeSketch() => {
                    if (running) {
                      progressCmd = None;
                      done.failure(new Exception("already running"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Resume());
                      running = true;
                    }
                  }
                  case Cmd.Exit() => {
                    vm.exit(0);
                    exitType = ExitType.ExitCmd;

                    progressCmd = None;
                    done.success(());
                  }
                }
              } else {
                done.failure(new Exception("already progress"));
              }
            }
            case TSlaveSyncCmd(cmd) => {
              cmd match {
                case SlaveSyncCmd.AddedEvents(events) => {
                  runtimeCmdQueue.add(
                    RuntimeCmd.AddedFrameStates(events)
                  );
                }
                case SlaveSyncCmd.LimitFrameCount(frameCount) => {
                  runtimeCmdQueue.add(
                    RuntimeCmd.LimitFrameCount(frameCount)
                  );
                }
              }
            }
            case TRuntimeEvent(event) => {
              event match {
                case RuntimeEvent.OnTargetFrameCount(screenshotPaths) => {
                  progressCmd match {
                    case Some(Cmd.StartSketch(), done) => {
                      eventListeners.foreach(
                        _(
                          Event.AddedScreenshots(
                            screenshotPaths
                          )
                        )
                      )
                      progressCmd = None;
                      done.success(());
                    }
                    case progressCmd => {
                      Logger.log("Unexpected event: OnTargetFrameCount");
                    }
                  }
                }
                case RuntimeEvent
                      .OnUpdateLocation(
                        frameCount,
                        trimMax,
                        frameStates,
                        windowX,
                        windowY,
                        screenshotPath
                      ) => {
                  screenshotPath.foreach { path =>
                    eventListeners.foreach(
                      _(
                        Event.AddedScreenshots(
                          Map(frameCount -> path)
                        )
                      )
                    )
                  }
                  eventListeners.foreach(
                    _(
                      Event.UpdateLocation(
                        frameCount,
                        trimMax,
                        frameStates,
                        windowX,
                        windowY
                      )
                    )
                  )
                }
                case RuntimeEvent.OnPaused => {
                  progressCmd match {
                    case Some(cmd: Cmd.PauseSketch, done) => {
                      progressCmd = None;
                      done.success(());
                    }
                    case progressCmd => {
                      Logger.log("Unexpected event: OnPaused");
                    }
                  }
                }
                case RuntimeEvent.OnResumed => {
                  progressCmd match {
                    case Some(cmd: Cmd.ResumeSketch, done) => {
                      progressCmd = None;
                      done.success(());
                    }
                    case progressCmd => {
                      Logger.log("Unexpected event: OnResumed");
                    }
                  }
                }
              }
            }
          }
        }
      } catch {
        case e: Exception => {
          Logger.err(e);
        }
      }

      threads.foreach(_.interrupt())
      progressCmd.foreach(
        _._2.failure(new Exception("unexpected vm exit"))
      );
      progressCmd = None;
      running = false;

      exitType match {
        case ExitType.Running |
            ExitType.Exception => { // ExitType.Runningは想定外のエラー
          vmForForceExit = Some(vm);
        }
        case _ => {}
      }

      if (exitType != ExitType.ExitCmd) {
        this.eventListeners.foreach(
          _(
            Event.Stopped()
          )
        )
      }

      exited = true;
    }).start();
  }

  def listen(listener: Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }

  def send(cmd: Cmd, done: Promise[Unit]) = {
    assert(!exited);
    taskQueue.add(TCmd(cmd, done));
  }

  def sendSlaveSync(cmd: SlaveSyncCmd) = {
    if (exited) {
      Logger.log("sendSlaveSync warning: vm is exited");
    }
    taskQueue.add(TSlaveSyncCmd(cmd));
  }

  // Stoppedイベントが発生した後のみ使用可能
  // 例外発生などで異常な状態だが動いているvmを強制終了する
  def forceExit() = {
    assert(exited);
    vmForForceExit.foreach(_.exit(0));
  }

  def isExited = exited
}
