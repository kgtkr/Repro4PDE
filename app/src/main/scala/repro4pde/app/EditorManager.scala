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
import java.io.PrintWriter
import processing.app.Base
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import repro4pde.runtime.shared.FrameState
import processing.app.RunnerListener
import java.util.Random
import cps.*
import cps.monads.{*, given}
import scala.util.boundary
import repro4pde.ui.shared.{
  EditorManagerCmd,
  EditorManagerEvent,
  Build,
  BuildCodeLine,
  BuildCodeToken,
  BuildCode
};

object EditorManager {
  class SlaveVm(
      val vmManager: VmManager,
      val buildId: Int,
      var pdeEventCount: Int
  ) {
    var frameCount = Int.MaxValue;
  }

  class MasterVm(val vmManager: VmManager, val slaves: MMap[Int, SlaveVm]) {}

  enum Task {
    case TCmd(cmd: EditorManagerCmd, done: Promise[Unit])
    case TMasterEvent(masterVm: MasterVm, event: VmManager.Event)
    case TSlaveEvent(slaveVm: SlaveVm, event: VmManager.Event)
  }
  export Task._

}

class EditorManager(val editor: JavaEditor) {
  import EditorManager._

  val taskQueue = new LinkedTransferQueue[Task]();
  var eventListeners = List[EditorManagerEvent => Unit]();
  val (config, logger) = Config.loadConfig(editor.getSketch().getFolder());

  var frameCount = 0;
  var maxFrameCount = 0;
  val frameStates = Buffer[FrameState]();
  var running = false;
  var currentBuild: (Int, JavaBuild) = null;
  val builds = Buffer[JavaBuild]();
  var oMasterVm: Option[MasterVm] = None;
  val slaves = MSet[Int]();
  var isExit = false;
  var masterLocation: Option[java.awt.Point] = None;
  val slaveLocations: MMap[Int, java.awt.Point] = MMap();
  val styles = new PdeTextAreaDefaults().styles;
  var prevCodes = Map[String, String]();
  val random = new Random();
  var randomSeed = random.nextLong();

  private def getCodes(): Array[(String, String)] = {
    editor
      .getSketch()
      .getCode()
      .map { code =>
        val name = code.getFile().toString();
        val text = code.getProgram();
        (name, text)
      }
  }

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
        val text = Files
          .readString(
            file.toPath(),
            StandardCharsets.UTF_8
          );
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
        logger.log(
          OperationLogger.Payload.CompileError(
            e.getMessage(),
            getCodes().toList
          )
        )
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
                  BuildCodeToken(
                    tokenStr,
                    (color.getRed(), color.getGreen(), color.getBlue()),
                    bold
                  )
                }
                .toList
              BuildCodeLine(i, line, tokens)
            }.toList;

          val name = code.getFileName();

          (name, BuildCode(name, lines))
        }
    )

    val buildId = this.builds.length;
    currentBuild = (buildId, javaBuild);

    this.builds += javaBuild;
    this.eventListeners.foreach(
      _(EditorManagerEvent.CreatedBuild(new Build(buildId, codes)))
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
    editor.clearConsole();
    eventListeners.foreach(_(EditorManagerEvent.ClearLog()));
    eventListeners.foreach(_(EditorManagerEvent.ClearedScreenshots()));

    val slaveVms = MMap[Int, SlaveVm](
      slaves.toSeq.map(id => (id -> createSlaveVm(id))): _*
    );
    val masterVmManager = new VmManager(
      javaBuild = currentBuild._2,
      slaveMode = false,
      runnerListener = new RunnerListenerEdtAdapter(editor) {
        override def statusError(x: Exception): Unit = {
          eventListeners.foreach(
            _(EditorManagerEvent.LogError(None, x.getMessage()))
          );
          super.statusError(x);
        }

        override def statusError(x: String): Unit = {
          eventListeners.foreach(
            _(EditorManagerEvent.LogError(None, x))
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
        javaBuild = builds(buildId),
        slaveMode = true,
        runnerListener = new RunnerListener {
          override def isHalted(): Boolean = false;
          override def startIndeterminate(): Unit = {}
          override def statusError(x$0: String): Unit = {
            eventListeners.foreach(
              _(EditorManagerEvent.LogError(Some(buildId), x$0))
            );
          }
          override def statusError(x$0: Exception): Unit = {
            eventListeners.foreach(
              _(EditorManagerEvent.LogError(Some(buildId), x$0.getMessage()))
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
      slaveVm.vmManager.send(VmManager.Cmd.Exit(), p);
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
          vmManager.send(VmManager.Cmd.Exit(), p);
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
          case TCmd(cmd, done) => {
            Await.ready(
              done
                .completeWith(processCmd(cmd))
                .future,
              Duration.Inf
            )
          }
          case TMasterEvent(vmms, event) => processMasterEvent(vmms, event)
          case TSlaveEvent(slaveVm, event) => {
            processSlaveEvent(slaveVm, event)
          }

        }

      }

      ()
    }).start();
  }

  private def processCmd(cmd: EditorManagerCmd): Future[Unit] = async[Future] {
    cmd match {
      case EditorManagerCmd.ReloadSketch(force) =>
        boundary {
          val newCodes = getCodes().toMap;
          if (newCodes == prevCodes && !force) {
            boundary.break(())
          }

          prevCodes = newCodes;
          blocking {
            this.updateBuild()
          }

          oMasterVm match {
            case Some(_) => {
              await(exitVm())
              await(startVm())
            }
            case None => {}
          }

        }
      case EditorManagerCmd.UpdateLocation(frameCount) => {
        oMasterVm match {
          case Some(_) => {
            await(exitVm())
            this.frameCount = frameCount;
            await(startVm())
          }
          case None => {
            this.frameCount = frameCount;
          }
        }
      }
      case EditorManagerCmd.StartSketch() => {
        oMasterVm match {
          case Some(masterVm) if masterVm.vmManager.isExited => {
            await(exitVm())
            // TODO: oMasterVm = None; ?
          }
          case _ => {}
        }

        oMasterVm match {
          case Some(_) => {
            throw new Exception("vm is already running")
          }
          case None => {
            blocking {
              editor.prepareRun();
              this.updateBuild();
              logger.log(
                OperationLogger.Payload.Start(
                  getCodes().toList
                )
              );
            }

            running = true;
            if (config.disableRepro) {
              frameCount = 0;
              maxFrameCount = 0;
              frameStates.clear();
              randomSeed = random.nextLong();
            }
            await(startVm())
          }
        }
      }
      case EditorManagerCmd.PauseSketch() => {
        oMasterVm match {
          case Some(masterVm) => {
            val p = Promise[Unit]();
            masterVm.vmManager.send(
              VmManager.Cmd.PauseSketch(),
              p
            )
            await(p.future)
            this.running = false;
          }
          case None => {
            throw new Exception("vm is not running");
          }
        }
      }
      case EditorManagerCmd.ResumeSketch() => {
        oMasterVm match {
          case Some(masterVm) => {
            val p = Promise[Unit]();
            masterVm.vmManager.send(
              VmManager.Cmd.ResumeSketch(),
              p
            )
            await(p.future)
            this.running = false;
          }
          case None => {
            throw new Exception("vm is not running");
          }
        }
      }
      case EditorManagerCmd.StopSketch() => {
        oMasterVm match {
          case None => {
            throw new Exception("vm is not running");
          }
          case Some(_) => {
            logger.log(OperationLogger.Payload.Stop());
            await(exitVm())
            running = false;
          }
        }
      }
      case EditorManagerCmd.Exit() => {
        oMasterVm match {
          case Some(_) => {
            await(exitVm())
            running = false;
          }
          case None => {
            running = false;
          }
        }

        isExit = true;
      }
      case EditorManagerCmd.AddSlave(id) => {
        if (slaves.contains(id)) {
          throw new Exception("slave is already added");
        } else {
          slaves += id;
          oMasterVm match {
            case Some(masterVm) => {
              val slaveVm = createSlaveVm(currentBuild._1);
              masterVm.slaves += (id -> slaveVm);
              val p = Promise[Unit]();
              blocking {
                slaveVm.vmManager.start(p)
              }
              await(p.future)
              updateSlaveVms();
            }
            case None => {}
          }
        }
      }
      case EditorManagerCmd.RemoveSlave(id) => {
        if (!slaves.contains(id)) {
          throw new Exception("slave is not added")
        } else {
          slaves -= id;
          oMasterVm match {
            case Some(masterVm) => {
              assert(masterVm.slaves.contains(id));
              await(exitSlaveVm(masterVm.slaves(id)))
              masterVm.slaves -= id;
              updateSlaveVms();
            }
            case None => {}
          }
        }
      }
      case EditorManagerCmd.RegenerateState() => {
        randomSeed = random.nextLong();
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
            EditorManagerEvent.UpdateLocation(
              frameCount,
              this.maxFrameCount
            )
          )
        )

        this.masterLocation = Some(new java.awt.Point(windowX, windowY));
      }
      case VmManager.Event.Stopped() => {
        this.eventListeners.foreach(_(EditorManagerEvent.Stopped(this.running)))
      }
      case VmManager.Event.AddedScreenshots(screenshotPaths) => {
        this.eventListeners.foreach(
          _(EditorManagerEvent.AddedScreenshots(screenshotPaths))
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

  def send(cmd: EditorManagerCmd, done: Promise[Unit]) = {
    taskQueue.put(TCmd(cmd, done));
  }

  def listen(listener: EditorManagerEvent => Unit) = {
    eventListeners = listener :: eventListeners;
  }
}
