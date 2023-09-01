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
import scalafx.scene.layout.VBox
import scalafx.scene.control.Button
import scalafx.beans.property.ObjectProperty
import scalafx.beans.property.BooleanProperty

object ControlPanel {
  def show(editor: JavaEditor): Unit = {
    editor.statusBusy();
    editor.clearConsole();
    editor.prepareRun();
    editor.activateRun();
    val sketchPath = editor.getSketch().getFolder().getAbsolutePath();
    val playing = BooleanProperty(false);
    val loading = BooleanProperty(false);
    val runner = new Runner(editor)

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
                  if (!loading.value) {
                    loading.value = true
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
          content = new VBox {
            padding = Insets(50, 80, 50, 80)
            children = Seq(
              new HBox {
                val slider = new Slider(0, 0, 0) {
                  disable <== loading
                  valueChanging.addListener({ (_, oldChanging, changing) =>
                    if (oldChanging && !changing && !loading.value) {
                      loading.value = true
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
                        if (!loading.value) {
                          slider.max = max2.toDouble / 60
                          if (!slider.valueChanging.value) {
                            slider.value = value2.toDouble / 60
                          }
                        }
                      }
                      case RunnerEvent.StartSketch() => {
                        loading.value = false
                      }
                      case RunnerEvent.PausedSketch() => {
                        loading.value = false
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
              },
              new HBox(10) {
                alignment = scalafx.geometry.Pos.Center
                children = Seq(
                  new Button {
                    text <== Bindings.createStringBinding(
                      () =>
                        if (playing.value) {
                          "⏸"
                        } else {
                          "▶"
                        },
                      playing
                    )
                    disable <== loading
                    onAction = _ => {
                      loading.value = true

                      if (playing.value) {
                        playing.value = false
                        runner.cmdQueue.add(RunnerCmd.PauseSketch())
                      } else {
                        playing.value = true
                        new Thread(() => {
                          runner.run()
                          Platform.runLater {
                            playing.value = false
                          }
                        }).start()
                      }

                    }
                  }
                )
              }
            )
          }

        };

        fxPanel.setScene(scene);

        SwingUtilities.invokeLater(() => {
          frame.pack();
        });
      });
    });
  }
}
