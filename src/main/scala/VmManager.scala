package net.kgtkr.seekprog;

import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.util.Arrays;
import java.util.Map;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.ClassType;
import com.sun.jdi.IntegerValue;
import com.sun.jdi.LongValue;
import com.sun.jdi.LocalVariable;
import com.sun.jdi.Location;
import com.sun.jdi.ObjectReference;
import com.sun.jdi.DoubleValue;
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
import java.nio.channels.ServerSocketChannel
import java.io.BufferedReader
import net.kgtkr.seekprog.runtime.RuntimeEvent
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import net.kgtkr.seekprog.runtime.PdeEventWrapper
import processing.app.RunnerListenerEdtAdapter
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
        events: List[List[PdeEventWrapper]]
    );
    case Stopped();
  }

  private type VmTask = EventSet | VmManager.Cmd | VmManager.SlaveSyncCmd |
    RuntimeEvent

}

class VmManager(
    val editorManager: EditorManager,
    val slaveBuildId: Option[Int] = None
) {
  val cmdQueue = new LinkedTransferQueue[VmManager.Cmd]();
  val slaveSyncCmdQueue = new LinkedTransferQueue[VmManager.SlaveSyncCmd]();
  var eventListeners = List[VmManager.Event => Unit]();
  var progressCmd: Option[VmManager.Cmd] = None;
  var running = false;
  val build = slaveBuildId
    .map(editorManager.builds(_))
    .getOrElse(editorManager.currentBuild);

  def run(done: Promise[Unit]) = {
    var isExpectedExit = false;

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
    running = editorManager.running;
    val javaBuild = build.javaBuild;
    val runner =
      new Runner(javaBuild, new RunnerListenerEdtAdapter(editorManager.editor));

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
    val vmTaskQueue = new LinkedTransferQueue[VmManager.VmTask]();
    val frameCount = editorManager.frameCount;
    val pdeEvents = editorManager.pdeEvents;
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
            vmTaskQueue.add(RuntimeEvent.fromJSON(line));
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
          vmTaskQueue.add(eventQueue.remove());
        }
      });
      thread.start();
      threads += thread;
    };
    {
      val thread = new Thread(() => {
        while (true) {
          vmTaskQueue.add(cmdQueue.take());
        }
      });
      thread.start();
      threads += thread;
    };
    {
      val thread = new Thread(() => {
        while (true) {
          vmTaskQueue.add(
            slaveSyncCmdQueue.take()
          );
        }
      });
      thread.start();
      threads += thread;
    };
    this.progressCmd = Some(VmManager.Cmd.StartSketch(done));

    new Thread(() => {
      try {
        while (true) {
          val vmTask = vmTaskQueue.take();
          vmTask match {
            case eventSet: EventSet => {
              for (evt <- eventSet.asScala) {
                println(evt);
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
                            vm.mirrorOf(frameCount),
                            vm.mirrorOf(
                              (if (slaveBuildId.isDefined)
                                 pdeEvents.toList.take(frameCount)
                               else pdeEvents.toList).asJson.noSpaces
                            ),
                            vm.mirrorOf(!running),
                            vm.mirrorOf(slaveBuildId.isDefined)
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
                  }
                  case _ => {}
                }
              }
            }
            case cmd: VmManager.Cmd => {
              if (progressCmd.isEmpty) {
                progressCmd = Some(cmd);

                cmd match {
                  case VmManager.Cmd.StartSketch(done) => {
                    progressCmd = None;
                    done.failure(new Exception("already started"));
                  }
                  case VmManager.Cmd.PauseSketch(done) => {
                    if (!running) {
                      progressCmd = None;
                      done.failure(new Exception("already paused"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Pause());
                      running = false;
                    }

                  }
                  case VmManager.Cmd.ResumeSketch(done) => {
                    if (running) {
                      progressCmd = None;
                      done.failure(new Exception("already running"));
                    } else {
                      runtimeCmdQueue.add(RuntimeCmd.Resume());
                      running = true;
                    }
                  }
                  case VmManager.Cmd.Exit(done) => {
                    running = false;
                    vm.exit(0);
                    threads.foreach(_.interrupt())
                    isExpectedExit = true;

                    progressCmd = None;
                    done.success(());
                  }
                }
              } else {
                cmd.done.failure(new Exception("already progress"));
              }
            }
            case cmd: VmManager.SlaveSyncCmd => {
              cmd match {
                case VmManager.SlaveSyncCmd.AddedEvents(events) => {
                  runtimeCmdQueue.add(
                    RuntimeCmd.AddedEvents(events)
                  );
                }
                case VmManager.SlaveSyncCmd.LimitFrameCount(frameCount) => {
                  runtimeCmdQueue.add(
                    RuntimeCmd.LimitFrameCount(frameCount)
                  );
                }
              }
            }
            case event: RuntimeEvent => {
              event match {
                case RuntimeEvent.OnTargetFrameCount => {
                  progressCmd match {
                    case Some(VmManager.Cmd.StartSketch(done)) => {
                      progressCmd = None;
                      done.success(());
                    }
                    case progressCmd => {
                      println("Unexpected event: OnTargetFrameCount");
                    }
                  }
                }
                case RuntimeEvent
                      .OnUpdateLocation(frameCount, trimMax, events) => {
                  eventListeners.foreach(
                    _(
                      VmManager.Event.UpdateLocation(
                        frameCount,
                        trimMax,
                        events
                      )
                    )
                  )
                }
                case RuntimeEvent.OnPaused => {
                  progressCmd match {
                    case Some(cmd: VmManager.Cmd.PauseSketch) => {
                      progressCmd = None;
                      cmd.done.success(());
                    }
                    case progressCmd => {
                      println("Unexpected event: OnPaused");
                    }
                  }
                }
                case RuntimeEvent.OnResumed => {
                  progressCmd match {
                    case Some(cmd: VmManager.Cmd.ResumeSketch) => {
                      progressCmd = None;
                      cmd.done.success(());
                    }
                    case progressCmd => {
                      println("Unexpected event: OnResumed");
                    }
                  }
                }
              }
            }
          }

          vm.resume();
        }
      } catch {
        case e: VMDisconnectedException => {
          threads.foreach(_.interrupt())
          println("VM is now disconnected.");
        }
        case e: Exception => {
          e.printStackTrace();
        }
      }

      if (!isExpectedExit) {
        progressCmd.foreach(
          _.done.failure(new Exception("unexpected vm exit"))
        );
        progressCmd = None;
        this.eventListeners.foreach(
          _(
            VmManager.Event.Stopped()
          )
        )
      }
    }).start();
  }

  def listen(listener: VmManager.Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
