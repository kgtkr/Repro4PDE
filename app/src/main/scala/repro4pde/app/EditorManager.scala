package repro4pde.app;

import scala.jdk.CollectionConverters._
import processing.mode.java.JavaBuild
import java.util.concurrent.LinkedTransferQueue
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
import javax.swing.text.Segment
import processing.app.syntax.PdeTextAreaDefaults
import processing.app.syntax.Token
import java.awt.Color
import processing.app.SketchException
import java.io.File
import processing.mode.java.preproc.PreprocessorResult
import scala.io.Source
import java.io.PrintWriter
import processing.app.Base
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import repro4pde.shared.FrameState
import processing.app.RunnerListener
import java.util.Random

object EditorManager {
  enum Cmd {
    val done: Promise[Unit];

    case ReloadSketch(done: Promise[Unit], force: Boolean)
    case UpdateLocation(
        frameCount: Int,
        done: Promise[Unit]
    )
    case StartSketch(done: Promise[Unit])
    case PauseSketch(done: Promise[Unit])
    case ResumeSketch(done: Promise[Unit])
    // TODO: このコマンドを送るUI実装
    case StopSketch(done: Promise[Unit])
    case Exit(done: Promise[Unit])
    case AddSlave(id: Int, done: Promise[Unit])
    case RemoveSlave(id: Int, done: Promise[Unit])
    case RegenerateState(done: Promise[Unit])

  }

  enum Event {
    case UpdateLocation(frameCount: Int, max: Int);
    case Stopped(playing: Boolean);
    case CreatedBuild(build: Build);
    case ClearLog();
    case LogError(slaveId: Option[Int], error: String);
    case AddedScreenshots(screenshotPaths: Map[Int, String]);
    case ClearedScreenshots();
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
  val config = Config.loadConfig(editor.getSketch().getFolder());

  var frameCount = 0;
  var maxFrameCount = 0;
  val frameStates = Buffer[FrameState]();
  var running = false;
  var currentBuild: Build = null;
  val builds = Buffer[Build]();
  var oMasterVm: Option[MasterVm] = None;
  val slaves = MSet[Int]();
  var isExit = false;
  var masterLocation: Option[java.awt.Point] = None;
  val slaveLocations: MMap[Int, java.awt.Point] = MMap();
  val styles = new PdeTextAreaDefaults().styles;
  var prevCodes = Map[String, String]();
  val random = new Random();
  var randomSeed = random.nextLong();

  private def updateBuild() = {
    editor.statusEmpty();
    editor.clearConsole();
    val javaBuild = new JavaBuild(editor.getSketch()) {
      override def preprocess(
          srcFolder: File,
          sizeWarning: Boolean
      ): PreprocessorResult = {
        val result = super.preprocess(srcFolder, sizeWarning);

        val className = result.getClassName();
        val file = new File(
          srcFolder,
          className + ".java"
        );
        val text = Source.fromFile(file).mkString;
        val newText = text.replaceFirst(
          "extends PApplet",
          "extends repro4pde.runtime.PAppletRuntime"
        );
        file.delete();
        val writer = new PrintWriter(file);
        writer.write(newText);
        writer.close();

        val libDir = Base
          .getSketchbookToolsFolder()
          .toPath()
          .resolve(
            Path.of(
              Repro4PDEApp.toolName,
              "tool",
              "lib"
            )
          );
        val cp = Files
          .readString(
            libDir.resolve("runtime-classpath.txt"),
            StandardCharsets.UTF_8
          )
          .split(",")
          .map(name => libDir.resolve(name.trim()).toString())
          .map(File.pathSeparator + _)
          .mkString("");

        val classPathField =
          classOf[JavaBuild].getDeclaredField("classPath");
        classPathField.setAccessible(true);
        classPathField.set(
          this,
          this.getClassPath()
            + cp
        );

        result
      }
    };
    try {
      javaBuild.build(true);
    } catch {
      case e: SketchException => {
        editor.statusError(e);
        throw e;
      }
    }
    val codes = Map.from(
      editor
        .getSketch()
        .getCode()
        .map { code =>
          val text = code.getProgram();
          val state =
            editor.getMode().getTokenMarker().createStateInstance();
          val lineTexts = text.split("\\r?\\n")
          state.insertLines(0, lineTexts.length)
          val lines =
            lineTexts.zipWithIndex.map { (line, i) =>
              val token = state.markTokens(
                new Segment(line.toCharArray(), 0, line.length),
                i
              );
              var offset = 0;
              val tokens = Iterator
                .iterate(token)(_.next)
                .takeWhile(_ != null)
                .takeWhile(_.id != Token.END)
                .map { token =>
                  val style = Option(styles(token.id));
                  val color = style.map(_.getColor()).getOrElse(Color.BLACK)
                  val bold = style.map(_.isBold()).getOrElse(false);

                  val tokenStr =
                    line.substring(offset, offset + token.length);
                  offset += token.length;
                  BuildCodeToken(tokenStr, color, bold)
                }
                .toList
              BuildCodeLine(i, line, tokens)
            }.toList;

          val name = code.getFileName();

          (name, BuildCode(name, lines))
        }
    )

    currentBuild = new Build(this.builds.length, javaBuild, codes);

    this.builds += currentBuild;
    this.eventListeners.foreach(
      _(Event.CreatedBuild(currentBuild))
    );
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
    eventListeners.foreach(_(Event.ClearLog()));
    eventListeners.foreach(_(Event.ClearedScreenshots()));

    val slaveVms = MMap[Int, SlaveVm](
      slaves.toSeq.map(id => (id -> createSlaveVm(id))): _*
    );
    val masterVmManager = new VmManager(
      javaBuild = currentBuild.javaBuild,
      slaveMode = false,
      runnerListener = new RunnerListenerEdtAdapter(editor) {
        override def statusError(x: Exception): Unit = {
          eventListeners.foreach(
            _(Event.LogError(None, x.getMessage()))
          );
          super.statusError(x);
        }

        override def statusError(x: String): Unit = {
          eventListeners.foreach(
            _(Event.LogError(None, x))
          );
          super.statusError(x);
        }
      },
      targetFrameCount = this.frameCount,
      defaultRunning = this.running,
      frameStates = this.frameStates.toList,
      randomSeed = this.randomSeed
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
        runnerListener = new RunnerListener {
          override def isHalted(): Boolean = false;
          override def startIndeterminate(): Unit = {}
          override def statusError(x$0: String): Unit = {
            eventListeners.foreach(
              _(Event.LogError(Some(buildId), x$0))
            );
          }
          override def statusError(x$0: Exception): Unit = {
            eventListeners.foreach(
              _(Event.LogError(Some(buildId), x$0.getMessage()))
            );
          }
          override def statusHalt(): Unit = {}
          override def statusNotice(x$0: String): Unit = {}
          override def stopIndeterminate(): Unit = {}
        },
        targetFrameCount = this.frameCount,
        defaultRunning = true,
        frameStates = this.frameStates.toList,
        randomSeed = this.randomSeed
      ),
      buildId,
      this.frameCount + 1
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
      try {
        updateBuild();
      } catch {
        case e: Exception => {
          // ignore
        }
      }
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

  private def processCmd(cmd: Cmd): Unit = {
    cmd match {
      case Cmd.ReloadSketch(done, force) => {
        val newCodes = editor
          .getSketch()
          .getCode()
          .map { code =>
            val name = code.getFile().toString();
            val text = code.getProgram();
            (name, text)
          }
          .toMap;
        if (newCodes == prevCodes && !force) {
          done.success(())
        } else {
          prevCodes = newCodes;
          try {
            this.updateBuild();
          } catch {
            case e: Exception => {
              done.failure(e);
              return;
            }
          }

          oMasterVm match {
            case Some(_) => {
              Await.ready(
                done
                  .completeWith(for {
                    _ <- exitVm()
                    _ <- startVm()
                  } yield ())
                  .future,
                Duration.Inf
              )
            }
            case None => {
              done.success(())
            }
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
              editor.prepareRun();
              this.updateBuild();
              this.config.log(
                LogPayload.Start(
                  editor
                    .getSketch()
                    .getCode()
                    .map { code =>
                      val name = code.getFile().toString();
                      val text = code.getProgram();
                      (name, text)
                    }
                    .toList
                )
              );
              running = true;
              if (config.disableRepro) {
                frameCount = 0;
                maxFrameCount = 0;
                frameStates.clear();
                randomSeed = random.nextLong();
              }
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
      case Cmd.StopSketch(done) => {
        oMasterVm match {
          case None => {
            done.failure(new Exception("vm is not running"));
          }
          case Some(_) => {
            this.config.log(LogPayload.Stop());
            Await.ready(
              done
                .completeWith(exitVm())
                .future,
              Duration.Inf
            )
            running = false;
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
      case Cmd.RegenerateState(done) => {
        randomSeed = random.nextLong();
        done.success(());
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

        if (this.maxFrameCount + 1 < this.frameStates.length) {
          this.frameStates.trimEnd(
            this.frameStates.length - (this.maxFrameCount + 1)
          );
        } else if (this.maxFrameCount + 1 > this.frameStates.length) {
          this.frameStates ++= Seq.fill(
            this.maxFrameCount + 1 - this.frameStates.length
          )(null /*ここのnullは後続の処理で消える*/ );
        }
        for ((event, i) <- events.zipWithIndex) {
          this.frameStates(frameCount - events.length + i + 1) = event;
        }
        assert(this.frameStates.forall(_ != null));
        for ((_, slaveVm) <- vmms.slaves.filter(!_._2.vmManager.isExited)) {
          slaveVm.vmManager.sendSlaveSync(
            VmManager.SlaveSyncCmd.AddedEvents(
              frameStates
                .take(
                  frameCount + 1
                )
                .drop(slaveVm.pdeEventCount)
                .toList
            )
          );
          slaveVm.pdeEventCount = frameCount + 1;
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
        this.eventListeners.foreach(_(Event.Stopped(this.running)))
      }
      case VmManager.Event.AddedScreenshots(screenshotPaths) => {
        this.eventListeners.foreach(
          _(Event.AddedScreenshots(screenshotPaths))
        )
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
      case VmManager.Event.Stopped() => {
        updateSlaveVms();
      }
      case VmManager.Event.AddedScreenshots(_) => {}
    }
  }

  def send(cmd: Cmd) = {
    taskQueue.put(TCmd(cmd));
  }

  def listen(listener: Event => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
