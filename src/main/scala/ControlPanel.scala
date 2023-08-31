package net.kgtkr.seekprog;

import scalafx.application.JFXApp3
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.effect.DropShadow
import scalafx.scene.layout.HBox
import scalafx.scene.paint.Color._
import scalafx.scene.paint._
import scalafx.scene.text.Text
import scalafx.Includes._
import scalafx.scene.control.Slider
import java.nio.file.FileSystems
import java.nio.file.Paths
import java.nio.file.StandardWatchEventKinds
import scala.jdk.CollectionConverters._
import java.nio.file.Path
import java.nio.file.WatchEvent
import com.sun.nio.file.SensitivityWatchEventModifier
import scalafx.application.Platform
import scalafx.beans.binding.Bindings
import processing.mode.java.JavaEditor
import javax.swing.SwingUtilities
import javax.swing.JFrame
import scalafx.embed.swing.SFXPanel
import processing.mode.java.JavaBuild

object ControlPanel {
  def show(editor: JavaEditor): Unit = {
    editor.statusBusy();
    editor.clearConsole();
    editor.prepareRun();
    editor.activateRun();
    val sketchPath = editor.getSketch().getFolder().getAbsolutePath();
    val runner = new Runner(editor)
    new Thread(() => runner.run()).start()

    var loading = true

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

      while (true) {
        val watchKey = watcher.take();

        for (event <- watchKey.pollEvents().asScala) {
          event.context() match {
            case filename: Path => {
              if (filename.toString().endsWith(".pde")) {
                Platform.runLater {
                  if (!loading) {
                    loading = true
                    runner.cmdQueue.add(RunnerCmd.ReloadSketch())
                  }

                }
              }
            }
            case evt => {
              println(s"unknown event: ${evt}")
            }
          }
        }

        if (!watchKey.reset()) {
          throw new RuntimeException("watchKey reset failed")
        }
      }
    }).start()

    SwingUtilities.invokeLater(() => {
      val frame = new JFrame("Seekprog");
      val fxPanel = new SFXPanel();
      frame.add(fxPanel);
      frame.setSize(300, 200);
      frame.setVisible(true);

      Platform.runLater(() => {
        val scene = new Scene {
          fill = Color.rgb(38, 38, 38)
          content = new HBox {
            padding = Insets(50, 80, 50, 80)
            val slider = new Slider(0, 0, 0) {
              valueChanging.addListener({ (_, _, changing) =>
                if (!changing && !loading) {
                  loading = true
                  runner.cmdQueue.add(
                    RunnerCmd.ReloadSketch(Some((value.value * 60).toInt))
                  );
                }
                ()
              })
            };
            runner.eventListeners = (event => {
              Platform.runLater {
                event match {
                  case RunnerEvent.UpdateLocation(value2, max2) => {
                    if (!loading) {
                      slider.max = max2.toDouble / 60
                      slider.value = value2.toDouble / 60
                    }
                  }
                  case RunnerEvent.StartSketch() => {
                    loading = false
                  }
                }
              }
            }) :: runner.eventListeners;

            children = Seq(
              slider,
              new Text {
                style = "-fx-font: normal bold 10pt sans-serif"
                fill = White
                text <== Bindings.createStringBinding(
                  () =>
                    f"${slider.value.intValue()}%d秒/ ${slider.max.intValue()}%d秒",
                  slider.value,
                  slider.max
                )
              }
            )
          }
        };

        fxPanel.setScene(scene);
      });
    });
  }
}
