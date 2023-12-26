package repro4pde.app

import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import java.nio.file.Path
import java.nio.file.WatchEvent
import com.sun.nio.file.SensitivityWatchEventModifier
import processing.mode.java.JavaEditor
import repro4pde.utils.ext._;
import scala.collection.mutable.Map as MMap
import processing.app.ui.EditorButton
import processing.app.ui.EditorToolbar
import processing.app.Language
import repro4pde.view.shared.{AppCmd, ViewCmd};
import scala.jdk.CollectionConverters._
import java.util.concurrent.LinkedTransferQueue
import scala.concurrent.Promise
import processing.app.Platform as PPlatform
import java.nio.file.Files
import processing.app.Base
import java.nio.charset.StandardCharsets
import java.io.File
import java.net.UnixDomainSocketAddress
import java.nio.channels.ServerSocketChannel
import java.net.StandardProtocolFamily
import io.circe._, io.circe.generic.semiauto._, io.circe.syntax._
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.channels.ClosedByInterruptException
import scala.util.chaining._
import processing.app.exec.StreamRedirectThread

object ViewManager {
  val instances = MMap[JavaEditor, ViewManager]();

  def show(editor: JavaEditor) = {
    val mViewManager = instances.synchronized {
      instances.get(editor)
    };

    mViewManager match {
      case Some(viewManager) => {
        viewManager.cmdQueue.add(ViewCmd.FocusRequest())
      }
      case None => {
        val viewManager = new ViewManager(editor)
        viewManager.start()
        instances.synchronized {
          instances(editor) = viewManager
        }
      }
    };
  }
}

class ViewManager(editor: JavaEditor) {
  val cmdQueue = new LinkedTransferQueue[ViewCmd]();
  val appCmdQueue = new LinkedTransferQueue[AppCmd]();

  def start() = {
    val runtimeDir = Files.createTempDirectory("repro4pde");
    runtimeDir.toFile().deleteOnExit();
    val sockPath = Path.of(runtimeDir.toString(), "repro4pde.sock");
    val sockAddr = UnixDomainSocketAddress.of(sockPath);
    val ssc = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
    ssc.bind(sockAddr);
    val sscThread =
      new Thread(() => {
        val sc = ssc.accept();

        val cmdThread = new Thread(() => {
          for (
            cmd <- Iterator
              .continually({
                try {
                  Some(cmdQueue.take())
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
        cmdThread.start();

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
                  cmdThread.interrupt();
                  null
                }
              }
            }
            .takeWhile(_ != null)
        ) {
          appCmdQueue.add(
            AppCmd.fromJSON(line)
          );
        }
        ()
      });
    sscThread.start();

    val sketchPath = editor.getSketch().getFolder().getAbsolutePath();
    val editorManager = new EditorManager(editor)
    editorManager.listen { event =>
      cmdQueue.add(ViewCmd.EditorManagerEvent(event))
    };

    val process = {
      val java = PPlatform.getJavaPath();
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
          libDir.resolve("view-classpath.txt"),
          StandardCharsets.UTF_8
        )
        .split(",")
        .map(name => libDir.resolve(name.trim()).toString())
        .pipe(filterCpUrls)
        .mkString(File.pathSeparator);
      val className = "repro4pde.view.View";

      new ProcessBuilder(
        java,
        "-cp",
        cp,
        className,
        sockPath.toString(),
        Language.getLanguage(),
        editorManager.config.asJson.noSpaces
      )
        .start();
    };

    val outThread = new StreamRedirectThread(
      "JVM stdout Reader",
      process.getInputStream(),
      System.out
    );
    outThread.start();

    val errThread = new StreamRedirectThread(
      "JVM stderr Reader",
      process.getErrorStream(),
      System.err
    );
    errThread.start();

    editorManager.start()

    // 実験用設定なので一度無効化したボタンを元に戻す必要はない
    if (editorManager.config.disablePdeButton) {
      val toolbar = editor.getToolbar()
      val runButtonField =
        classOf[EditorToolbar].getDeclaredField("runButton")
      runButtonField.setAccessible(true)
      val runButton = runButtonField
        .get(toolbar)
        .asInstanceOf[EditorButton];
      runButton.setVisible(false)

      val stopButtonField =
        classOf[EditorToolbar].getDeclaredField("stopButton")
      stopButtonField.setAccessible(true)
      val stopButton = stopButtonField
        .get(toolbar)
        .asInstanceOf[EditorButton];
      stopButton.setVisible(false)

    }

    val fileWatchThread =
      new Thread(() => {
        val watcher = FileSystems.getDefault().newWatchService();
        val path = Paths.get(sketchPath);
        path.register(
          watcher,
          Array[WatchEvent.Kind[?]](
            StandardWatchEventKinds.ENTRY_CREATE,
            StandardWatchEventKinds.ENTRY_DELETE,
            StandardWatchEventKinds.ENTRY_MODIFY
          ),
          SensitivityWatchEventModifier.HIGH
        );

        for (
          watchKey <- Iterator
            .continually({
              try {
                Some(watcher.take())
              } catch {
                case e: InterruptedException => {
                  None
                }
              }
            })
            .mapWhile(identity)
        ) {
          for (event <- watchKey.pollEvents().asScala) {
            event.context() match {
              case filename: Path => {
                if (filename.toString().endsWith(".pde")) {
                  cmdQueue.add(
                    ViewCmd.FileChanged()
                  )
                }
              }
              case evt => {
                Logger.log(s"unknown event: ${evt}")
              }
            }
          }

          if (!watchKey.reset()) {
            throw new RuntimeException("watchKey reset failed")
          }
        }
      });
    fileWatchThread.start();

    val cmdProcessThread = new Thread(() => {
      var noExit = true;
      while (noExit) {
        val cmd = appCmdQueue.take();
        cmd match {
          case AppCmd.EditorManagerCmd(cmd, requestId) => {
            import scala.concurrent.ExecutionContext.Implicits.global
            val done = Promise[Unit]();
            editorManager.send(cmd, done);
            done.future.onComplete(result => {
              cmdQueue.add(
                ViewCmd.EditorManagerCmdFinished(
                  requestId,
                  result.failed.toOption.map(_.getMessage())
                )
              )
            })
          }
          case AppCmd.Exit() => {
            fileWatchThread.interrupt();
            sscThread.interrupt();
            ViewManager.instances.synchronized {
              ViewManager.instances.remove(editor)
            }
            noExit = false;
          }
        }
      }
    });
    cmdProcessThread.start();
  }

  def filterCpUrls(paths: Array[String]) = {
    val allPlatforms = Seq(
      "linux",
      "linux-aarch64",
      "mac-aarch64",
      "mac",
      "win"
    );

    val osName = System.getProperty("os.name").toLowerCase();
    val osArch = System.getProperty("os.arch").toLowerCase();

    val platformOs = if (osName.startsWith("linux")) {
      "linux"
    } else if (osName.startsWith("mac")) {
      "mac"
    } else if (osName.startsWith("windows")) {
      "win"
    } else {
      throw new Exception("Unsupported OS: " + osName)
    };

    val platformArch = if (osArch == "aarch64") {
      "-aarch64"
    } else if (osArch == "x86_64") {
      ""
    } else if (osArch == "amd64") {
      ""
    } else {
      throw new Exception("Unsupported arch: " + osArch)
    };

    val platform = platformOs + platformArch;

    paths.filter(path => {
      val name = path.substring(path.lastIndexOf("/") + 1);
      !name.startsWith("javafx-") || !allPlatforms.exists(platform =>
        name.endsWith("-" + platform + ".jar")
      ) || name.endsWith("-" + platform + ".jar")
    })
  }
}
