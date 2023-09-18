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
import javax.swing.WindowConstants
import java.awt.event.WindowAdapter
import net.kgtkr.seekprog.ext._;
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure
import scalafx.beans.property.BufferProperty
import scalafx.scene.control.ScrollPane
import scalafx.scene.control.TableView
import scalafx.scene.control.ListView
import scalafx.scene.control.ListCell
import scalafx.scene.control.CheckBox
import scalafx.scene.control.cell.CheckBoxListCell

enum PlayerState {
  case Playing;
  case Paused;
  case Stopped;
}

object ControlPanel {
  def init() = {
    Platform.implicitExit = false;
  }

  def show(editor: JavaEditor) = {
    val sketchPath = editor.getSketch().getFolder().getAbsolutePath();
    val loading = BooleanProperty(false);
    val editorManager = new EditorManager(editor)
    editorManager.start()
    val playerState = ObjectProperty(PlayerState.Stopped);
    val currentBuildIdProperty = ObjectProperty[Option[Int]](None);
    val slaveBuildIdProperty = ObjectProperty[Option[Int]](None);

    def donePromise(onSuccess: => Unit = {}) = {
      import scala.concurrent.ExecutionContext.Implicits.global

      val promise = Promise[Unit]();
      promise.future.onComplete(result => {
        Platform.runLater {
          loading.value = false
        }

        result match {
          case Success(_) => {
            onSuccess
          }
          case Failure(e) => {
            e.printStackTrace();
          }
        }
      })
      promise
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
                  Platform.runLater {
                    if (!loading.value) {
                      loading.value = true
                      editorManager.send(
                        EditorManager.Cmd.ReloadSketch(donePromise())
                      )
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
      });
    fileWatchThread.start();

    SwingUtilities.invokeLater(() => {
      val frame = new JFrame("Seekprog");
      val fxPanel = new SFXPanel();
      frame.add(fxPanel);
      frame.setSize(300, 200);
      frame.setVisible(true);
      frame.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
      frame.addWindowListener(new WindowAdapter() {
        override def windowClosing(e: java.awt.event.WindowEvent) = {
          Platform.runLater {
            loading.value = true
            editorManager.send(
              EditorManager.Cmd.Exit(donePromise())
            );
            frame.dispose()
            fileWatchThread.interrupt();
          }
        }
      });

      Platform.runLater(() => {
        val scene = new Scene {
          fill = Color.rgb(240, 240, 240)
          content = new VBox {
            style = "-fx-font: normal bold 10pt sans-serif"
            padding = Insets(50, 80, 50, 80)
            children = Seq(
              new HBox {
                val slider = new Slider(0, 0, 0) {
                  disable <== loading
                  valueChanging.addListener({ (_, oldChanging, changing) =>
                    if (oldChanging && !changing && !loading.value) {
                      loading.value = true
                      editorManager.send(
                        EditorManager.Cmd.UpdateLocation(
                          (value.value * 60).toInt,
                          donePromise()
                        )
                      );
                    }
                    ()
                  })
                };
                editorManager.listen { event =>
                  Platform.runLater {
                    event match {
                      case EditorManager.Event
                            .UpdateLocation(value2, max2) => {
                        slider.max = max2.toDouble / 60
                        if (!slider.valueChanging.value) {
                          slider.value = value2.toDouble / 60
                        }
                      }
                      case EditorManager.Event.Stopped() => {
                        playerState.value = PlayerState.Stopped
                      }
                      case EditorManager.Event.CreatedBuild(build) => {
                        currentBuildIdProperty.value = Some(build.id)
                      }
                    }
                  }
                };

                children = Seq(
                  slider,
                  new Text {
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
                        if (playerState.value == PlayerState.Playing) {
                          "⏸"
                        } else {
                          "▶"
                        },
                      playerState
                    )
                    disable <== loading
                    onAction = _ => {
                      playerState.value match {
                        case PlayerState.Playing => {
                          loading.value = true
                          editorManager.send(
                            EditorManager.Cmd.PauseSketch(donePromise {
                              Platform.runLater {
                                playerState.value = PlayerState.Paused;
                              }
                            })
                          )
                        }
                        case PlayerState.Paused => {
                          loading.value = true
                          editorManager.send(
                            EditorManager.Cmd.ResumeSketch(donePromise {
                              Platform.runLater {
                                playerState.value = PlayerState.Playing;
                              }
                            })
                          )
                        }
                        case PlayerState.Stopped => {
                          loading.value = true
                          editorManager.send(
                            EditorManager.Cmd.StartSketch(donePromise {
                              Platform.runLater {
                                playerState.value = PlayerState.Playing;
                              }
                            })
                          )
                        }
                      }
                    }
                  },
                  new Button {
                    text <== Bindings.createStringBinding(
                      () =>
                        if (slaveBuildIdProperty.value.isDefined) {
                          "並列実行を無効化する"
                        } else {
                          "並列実行を有効化する"
                        },
                      slaveBuildIdProperty
                    )
                    disable <==
                      Bindings.createBooleanBinding(
                        () =>
                          loading.value || currentBuildIdProperty.value.isEmpty,
                        loading,
                        currentBuildIdProperty
                      )
                    onAction = _ => {
                      currentBuildIdProperty.value match {
                        case Some(currentBuildId) if !loading.value => {
                          slaveBuildIdProperty.value match {
                            case Some(slaveBuildId) => {
                              loading.value = true
                              editorManager.send(
                                EditorManager.Cmd.RemoveSlave(
                                  slaveBuildId,
                                  donePromise {
                                    Platform.runLater {
                                      slaveBuildIdProperty.value = None
                                    }
                                  }
                                )
                              )
                            }
                            case None => {
                              loading.value = true
                              editorManager.send(
                                EditorManager.Cmd.AddSlave(
                                  currentBuildId,
                                  donePromise {
                                    Platform.runLater {
                                      slaveBuildIdProperty.value =
                                        Some(currentBuildId)
                                    }
                                  }
                                )
                              )
                            }
                          }
                        }
                        case _ => {}
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
