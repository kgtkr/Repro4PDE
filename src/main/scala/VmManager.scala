package net.kgtkr.seekprog;

import java.io.InputStreamReader;
import java.util.Arrays;
import com.sun.jdi.ClassType;
import com.sun.jdi.Value;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.event.BreakpointEvent;
import com.sun.jdi.event.ClassPrepareEvent;
import com.sun.jdi.event.EventSet;
import com.sun.jdi.event.ExceptionEvent;
import java.io.File;
import scala.jdk.CollectionConverters._
import processing.app.Base
import processing.mode.java.JavaBuild
import java.util.concurrent.LinkedTransferQueue
import java.nio.channels.ServerSocketChannel
import java.io.BufferedReader
import net.kgtkr.seekprog.runtime.RuntimeEvent
import java.nio.charset.StandardCharsets
import io.circe._, io.circe.syntax._
import net.kgtkr.seekprog.runtime.PdeEventWrapper
import processing.mode.java.runner.Runner
import net.kgtkr.seekprog.runtime.RuntimeCmd
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.Path
import java.net.UnixDomainSocketAddress
import java.net.StandardProtocolFamily
import net.kgtkr.seekprog.ext._;
import net.kgtkr.seekprog.tool.SeekprogTool
import scala.concurrent.Promise
import java.nio.channels.Channels
import scala.collection.mutable.Buffer
import processing.app.RunnerListener

object VmManager {
  enum SlaveSyncCmd {
    case AddedEvents(events: List[List[PdeEventWrapper]]);
    case LimitFrameCount(frameCount: Int);
  }

  enum Cmd {
    val done: Promise[Unit];

    case StartSketch(done: Promise[Unit]) // for internal
    case PauseSketch(done: Promise[Unit])
    case ResumeSketch(done: Promise[Unit])
    case Exit(done: Promise[Unit])

  }

  enum Event {
    case UpdateLocation(
        frameCount: Int,
        trimMax: Boolean,
        events: List[List[PdeEventWrapper]],
        windowX: Int,
        windowY: Int
    );
    case Stopped();
  }

  enum Task {
    case TVmEvent(eventSet: EventSet);
    case TCmd(cmd: Cmd);
    case TSlaveSyncCmd(cmd: SlaveSyncCmd);
    case TRuntimeEvent(event: RuntimeEvent);
  }
  export Task._;

  enum ExitType {
    case Running;
    case ExitCmd;
    case Exception;
  }

}

class VmManager(
    val javaBuild: JavaBuild,
    val slaveMode: Boolean,
    val runnerListener: RunnerListener,
    val targetFrameCount: Int,
    val defaultRunning: Boolean,
    val pdeEvents: List[List[PdeEventWrapper]]
) {
  import VmManager._;

  var eventListeners = List[Event => Unit]();
  var progressCmd: Option[Cmd] = None;
  var running = false;
  val taskQueue = new LinkedTransferQueue[Task]();
  @volatile
  var vmForForceExit: Option[VirtualMachine] = None;
  @volatile
  var exited = false;

  def start(done: Promise[Unit]) = {
    var exitType = ExitType.Running;

    val sockPath = {
      val tempDir = Files.createTempDirectory("seekprog");
      tempDir.toFile().deleteOnExit();
      Path.of(tempDir.toString(), "seekprog.sock")
    }

    val sockAddr = UnixDomainSocketAddress.of(sockPath);
    val ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    ssc.bind(sockAddr);

    val toolDir = new File(
      new File(
        Base
          .getSketchbookToolsFolder(),
        SeekprogTool.toolName
      ),
      "tool"
    );
    running = defaultRunning;
    val runner =
      new Runner(javaBuild, runnerListener);

    val cp = toolDir
      .listFiles()
      .map(File.pathSeparator + _.getAbsolutePath())
      .mkString("");
    val classPathField =
      javaBuild.getClass().getDeclaredField("classPath");
    classPathField.setAccessible(true);
    classPathField.set(
      javaBuild,
      javaBuild.getClassPath()
        + cp
    );

    val vm =
      runner.debug(Array(sockPath.toString()));

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
        while (true) {
          taskQueue.add(TVmEvent(eventQueue.remove()));
        }
      });
      thread.start();
      threads += thread;
    };
    this.progressCmd = Some(Cmd.StartSketch(done));

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
                              classOf[runtime.RuntimeMain].getName()
                            )
                          ),
                          0
                        );

                        val RuntimeMainClassType = vm
                          .classesByName(
                            classOf[runtime.RuntimeMain].getName()
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
                            vm.mirrorOf(targetFrameCount),
                            vm.mirrorOf(
                              (if (slaveMode)
                                 pdeEvents.toList.take(targetFrameCount)
                               else pdeEvents.toList).asJson.noSpaces
                            ),
                            vm.mirrorOf(!running),
                            vm.mirrorOf(slaveMode)
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
                  case _ => {}
                }
              }
            }
            case TCmd(cmd) => {
              if (progressCmd.isEmpty) {
                progressCmd = Some(cmd);

                cmd match {
                  case Cmd.StartSketch(done) => {
                    progressCmd = None;
                    done.failure(new Exception("already started"));
                  }
                  case Cmd.PauseSketch(done) => {
                    if (!running) {
                      progressCmd = None;
                      done.failure(new Exception("already paused"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Pause());
                      running = false;
                    }

                  }
                  case Cmd.ResumeSketch(done) => {
                    if (running) {
                      progressCmd = None;
                      done.failure(new Exception("already running"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Resume());
                      running = true;
                    }
                  }
                  case Cmd.Exit(done) => {
                    vm.exit(0);
                    exitType = ExitType.ExitCmd;

                    progressCmd = None;
                    done.success(());
                  }
                }
              } else {
                cmd.done.failure(new Exception("already progress"));
              }
            }
            case TSlaveSyncCmd(cmd) => {
              cmd match {
                case SlaveSyncCmd.AddedEvents(events) => {
                  runtimeCmdQueue.add(
                    RuntimeCmd.AddedEvents(events)
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
                case RuntimeEvent.OnTargetFrameCount => {
                  progressCmd match {
                    case Some(Cmd.StartSketch(done)) => {
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
                        events,
                        windowX,
                        windowY
                      ) => {
                  eventListeners.foreach(
                    _(
                      Event.UpdateLocation(
                        frameCount,
                        trimMax,
                        events,
                        windowX,
                        windowY
                      )
                    )
                  )
                }
                case RuntimeEvent.OnPaused => {
                  progressCmd match {
                    case Some(cmd: Cmd.PauseSketch) => {
                      progressCmd = None;
                      cmd.done.success(());
                    }
                    case progressCmd => {
                      Logger.log("Unexpected event: OnPaused");
                    }
                  }
                }
                case RuntimeEvent.OnResumed => {
                  progressCmd match {
                    case Some(cmd: Cmd.ResumeSketch) => {
                      progressCmd = None;
                      cmd.done.success(());
                    }
                    case progressCmd => {
                      Logger.log("Unexpected event: OnResumed");
                    }
                  }
                }
              }
            }
          }

          if (exitType != ExitType.ExitCmd) {
            vm.resume();
          }
        }
      } catch {
        case e: Exception => {
          Logger.err(e);
        }
      }

      threads.foreach(_.interrupt())
      progressCmd.foreach(
        _.done.failure(new Exception("unexpected vm exit"))
      );
      progressCmd = None;
      running = false;

      exitType match {
        case ExitType.Running | ExitType.Exception => {
          vmForForceExit = Some(vm);
          this.eventListeners.foreach(
            _(
              Event.Stopped()
            )
          )
        }
        case _ => {}
      }
      exited = true;
    }).start();
  }

  def listen(listener: Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }

  def send(cmd: Cmd) = {
    assert(!exited);
    taskQueue.add(TCmd(cmd));
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
