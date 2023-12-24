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
import repro4pde.view.View

object ControlPanel {
  val instances = MMap[JavaEditor, View]();

  def show(editor: JavaEditor) = {
    val mView = instances.synchronized {
      instances.get(editor)
    };

    mView match {
      case Some(view) => {
        view.cmdQueue.add(ViewCmd.FocusRequest())
      }
      case None => {
        val sketchPath = editor.getSketch().getFolder().getAbsolutePath();

        val editorManager = new EditorManager(editor)
        val view = new View(Language.getLanguage(), editorManager.config)

        editorManager.listen { event =>
          view.cmdQueue.add(ViewCmd.EditorManagerEvent(event))
        };
        editorManager.start()
        view.start();

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
                      view.cmdQueue.add(
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
            val cmd = view.appCmdQueue.take();
            cmd match {
              case AppCmd.EditorManagerCmd(cmd, done) => {
                editorManager.send(cmd, done);
              }
              case AppCmd.Exit() => {
                fileWatchThread.interrupt();
                instances.synchronized {
                  instances.remove(editor)
                }
                noExit = false;
              }
            }
          }
        });
        cmdProcessThread.start();

        instances.synchronized {
          instances(editor) = view
        }
      }
    };
  }
}
