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

enum VmManagerCmd {
  val done: Promise[Unit];

  case StartSketch(done: Promise[Unit]) // for internal
  case PauseSketch(done: Promise[Unit])
  case ResumeSketch(done: Promise[Unit])
  case Exit(done: Promise[Unit])
}

enum VmManagerEvent {
  case UpdateLocation(
      frameCount: Int,
      trimMax: Boolean,
      events: List[List[PdeEventWrapper]]
  );
  case Stopped();
}

class VmManager(
    val editorManager: EditorManager
) {
  val cmdQueue = new LinkedTransferQueue[VmManagerCmd]();
  var eventListeners = List[VmManagerEvent => Unit]();
  var progressCmd: Option[VmManagerCmd] = None;
  var running = false;

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
    val build = editorManager.build;
    val runner =
      new Runner(build, new RunnerListenerEdtAdapter(editorManager.editor));

    val cp = toolDir
      .listFiles()
      .map(File.pathSeparator + _.getAbsolutePath())
      .mkString("");
    val classPathField =
      build.getClass().getDeclaredField("classPath");
    classPathField.setAccessible(true);
    classPathField.set(
      build,
      build.getClassPath()
        + cp
    );

    val vm =
      runner.debug(Array(sockPath.toString()));

    val classPrepareRequest =
      vm.eventRequestManager().createClassPrepareRequest();
    classPrepareRequest.addClassFilter(build.getSketchClassName());
    classPrepareRequest.enable();

    val exceptionRequest =
      vm.eventRequestManager().createExceptionRequest(null, false, true);
    exceptionRequest.enable();

    val runtimeCmdQueue = new LinkedTransferQueue[RuntimeCmd]();
    val runtimeEventQueue = new LinkedTransferQueue[RuntimeEvent]();
    val frameCount = editorManager.frameCount;
    val pdeEvents = editorManager.pdeEvents;

    val runtimeEventThread =
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

        val buf = ByteBuffer.allocate(1024);
        val sBuf = new StringBuffer();

        while ({
          try {
            sc.read(buf) != -1
          } catch {
            case e: ClosedByInterruptException => {
              runtimeCmdThread.interrupt();
              false
            }
          }
        }) {
          buf.flip();
          sBuf.append(StandardCharsets.UTF_8.decode(buf));
          if (sBuf.charAt(sBuf.length() - 1) == '\n') {
            runtimeEventQueue.add(RuntimeEvent.fromJSON(sBuf.toString()));
            sBuf.setLength(0);
          }

          buf.clear()
        }
        ()
      });
    runtimeEventThread.start();
    this.progressCmd = Some(VmManagerCmd.StartSketch(done));

    new Thread(() => {
      try {
        while (true) {
          val eventSet = Option(vm.eventQueue().remove(200))
            .map(_.asScala)
            .getOrElse(Seq.empty);
          for (evt <- eventSet) {
            println(evt);
            evt match {
              case evt: ClassPrepareEvent => {
                val classType = evt.referenceType().asInstanceOf[ClassType];
                if (classType.name() == build.getSketchClassName()) {
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
                          pdeEvents.toList.asJson.noSpaces
                        ),
                        vm.mirrorOf(!running)
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

          if (progressCmd.isEmpty) {
            Option(cmdQueue.poll()).foreach(cmd => {
              progressCmd = Some(cmd);

              cmd match {
                case VmManagerCmd.StartSketch(done) => {
                  progressCmd = None;
                  done.failure(new Exception("already started"));
                }
                case VmManagerCmd.PauseSketch(done) => {
                  if (!running) {
                    progressCmd = None;
                    done.failure(new Exception("already paused"));
                  } else {
                    runtimeCmdQueue.add(RuntimeCmd.Pause());
                    running = false;
                  }

                }
                case VmManagerCmd.ResumeSketch(done) => {
                  if (running) {
                    progressCmd = None;
                    done.failure(new Exception("already running"));
                  } else {
                    runtimeCmdQueue.add(RuntimeCmd.Resume());
                    running = true;
                  }
                }
                case VmManagerCmd.Exit(done) => {
                  running = false;
                  vm.exit(0);
                  runtimeEventThread.interrupt();
                  isExpectedExit = true;

                  progressCmd = None;
                  done.success(());
                }
              }
            })
          }

          for (
            event <- Iterator
              .continually(Option(runtimeEventQueue.poll()))
              .mapWhile(identity)
          ) {
            event match {
              case RuntimeEvent.OnTargetFrameCount => {
                progressCmd match {
                  case Some(VmManagerCmd.StartSketch(done)) => {
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
                    VmManagerEvent.UpdateLocation(
                      frameCount,
                      trimMax,
                      events
                    )
                  )
                )
              }
              case RuntimeEvent.OnPaused => {
                progressCmd match {
                  case Some(cmd: VmManagerCmd.PauseSketch) => {
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
                  case Some(cmd: VmManagerCmd.ResumeSketch) => {
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

          vm.resume();
        }
      } catch {
        case e: VMDisconnectedException => {
          runtimeEventThread.interrupt();
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
            VmManagerEvent.Stopped()
          )
        )
      }
    }).start();
  }

  def listen(listener: VmManagerEvent => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
