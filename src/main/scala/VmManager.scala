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
import net.kgtkr.seekprog.runtime.EventWrapper
import processing.app.RunnerListenerEdtAdapter
import processing.mode.java.runner.Runner
import net.kgtkr.seekprog.runtime.RuntimeCmd
import java.nio.channels.ClosedByInterruptException
import java.nio.file.Files
import java.nio.file.Path
import java.net.UnixDomainSocketAddress
import java.net.StandardProtocolFamily
import net.kgtkr.seekprog.ext._;

class VmManager(
    val editorManager: EditorManager
) {
  var continueOnExit = false
  def run() = {
    val sockPath = {
      val tempDir = Files.createTempDirectory("seekprog");
      tempDir.toFile().deleteOnExit();
      Path.of(tempDir.toString(), "seekprog.sock")
    }

    val sockAddr = UnixDomainSocketAddress.of(sockPath);
    val ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    ssc.bind(sockAddr);

    val sketch = editorManager.editor.getSketch();
    val build = new JavaBuild(sketch);
    val toolDir = new File(
      new File(
        Base
          .getSketchbookToolsFolder(),
        "Seekprog"
      ),
      "tool"
    );
    val mainClassName = build.build(true);
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
    classPrepareRequest.addClassFilter(mainClassName);
    classPrepareRequest.enable();

    editorManager.eventListeners.foreach(_(EditorManagerEvent.StartSketch()))

    val runtimeCmdQueue = new LinkedTransferQueue[RuntimeCmd]();

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
            RuntimeEvent.fromJSON(sBuf.toString()) match {
              case RuntimeEvent.OnTargetFrameCount => {}
              case RuntimeEvent
                    .OnUpdateLocation(frameCount, trimMax, events) => {
                editorManager.cmdQueue.add(
                  EditorManagerCmd.UpdateLocation(frameCount, trimMax, events)
                );
              }
              case RuntimeEvent.OnPaused => {
                editorManager.eventListeners.foreach(
                  _(EditorManagerEvent.PausedSketch())
                )
              }
              case RuntimeEvent.OnResumed => {
                editorManager.eventListeners.foreach(
                  _(EditorManagerEvent.ResumedSketch())
                )
              }
            }
            sBuf.setLength(0);
          }

          buf.clear()
        }
        ()
      });
    runtimeEventThread.start();

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
              if (classType.name() == mainClassName) {
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
                      .methodsByName("run")
                      .get(0),
                    Arrays.asList(
                      instance,
                      vm.mirrorOf(editorManager.frameCount),
                      vm.mirrorOf(
                        editorManager.events.toList.asJson.noSpaces
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
            case _ => {}
          }
        }

        for (
          cmd <- Iterator
            .continually(Option(editorManager.cmdQueue.poll()))
            .mapWhile(identity)
        ) {
          cmd match {
            case EditorManagerCmd.ReloadSketch(frameCount) => {
              println("Reloading sketch...");
              frameCount.foreach { frameCount =>
                editorManager.frameCount = frameCount
              }
              vm.exit(0);
              this.continueOnExit = true;
            }
            case EditorManagerCmd.UpdateLocation(
                  frameCount,
                  trimMax,
                  events
                ) => {
              editorManager.frameCount = frameCount;
              editorManager.maxFrameCount = if (trimMax) {
                frameCount
              } else {
                Math.max(editorManager.maxFrameCount, frameCount);
              };

              if (editorManager.maxFrameCount < editorManager.events.length) {
                editorManager.events.trimEnd(
                  editorManager.events.length - editorManager.maxFrameCount
                );
              } else if (
                editorManager.maxFrameCount > editorManager.events.length
              ) {
                editorManager.events ++= Seq.fill(
                  editorManager.maxFrameCount - editorManager.events.length
                )(List());
              }
              for ((event, i) <- events.zipWithIndex) {
                editorManager.events(frameCount - events.length + i) = event;
              }
              editorManager.eventListeners.foreach(
                _(
                  EditorManagerEvent.UpdateLocation(
                    frameCount,
                    editorManager.maxFrameCount
                  )
                )
              )
            }
            case EditorManagerCmd.PauseSketch() => {
              runtimeCmdQueue.add(RuntimeCmd.Pause());
            }
            case EditorManagerCmd.ResumeSketch() => {
              runtimeCmdQueue.add(RuntimeCmd.Resume());
            }
            case EditorManagerCmd.Exit() => {
              vm.exit(0);
              runtimeEventThread.interrupt();
              editorManager.eventListeners.foreach(
                _(EditorManagerEvent.Exited())
              );
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
  }
}
