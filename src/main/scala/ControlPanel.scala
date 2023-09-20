package net.kgtkr.seekprog;

import scalafx.geometry.Insets
import scalafx.scene.Scene
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
import scalafx.scene.layout.VBox
import scalafx.scene.control.Button
import scalafx.beans.property.ObjectProperty
import scalafx.beans.property.BooleanProperty
import net.kgtkr.seekprog.ext._;
import scala.concurrent.Promise
import scala.util.Success
import scala.util.Failure
import scalafx.scene.Node
import com.github.difflib.DiffUtils
import scalafx.scene.layout.Background
import scalafx.scene.layout.BackgroundFill
import scalafx.scene.text.TextFlow
import scalafx.scene.layout.GridPane
import scalafx.scene.layout.ColumnConstraints
import scalafx.scene.layout.Pane
import scalafx.stage.Stage

enum PlayerState {
  case Playing;
  case Paused;
  case Stopped;
}

object ControlPanel {
  def init() = {
    Platform.implicitExit = false;
    Platform.startup(() => {});
  }

  def show(editor: JavaEditor) = {
    val sketchPath = editor.getSketch().getFolder().getAbsolutePath();
    val loading = BooleanProperty(false);
    val editorManager = new EditorManager(editor)
    editorManager.start()
    val playerState = ObjectProperty(PlayerState.Stopped);
    val currentBuildProperty = ObjectProperty[Option[Build]](None);
    val slaveBuildProperty = ObjectProperty[Option[Build]](None);
    val diffNodeProperty = ObjectProperty[Node](new VBox());

    def updateDiffNodeProperty() = {
      (slaveBuildProperty.value, currentBuildProperty.value) match {
        case (Some(slaveBuild), Some(currentBuild)) => {
          diffNodeProperty.value = createDiffNode(slaveBuild, currentBuild)
        }
        case _ => {
          diffNodeProperty.value = new VBox()
        }
      }
    }

    currentBuildProperty.onChange { (_, _, _) =>
      updateDiffNodeProperty()
    }

    slaveBuildProperty.onChange { (_, _, _) =>
      updateDiffNodeProperty()
    }

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
            Logger.err(e);
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

    Platform.runLater(() => {

      val stage = new Stage {
        title = "Seekprog"
        onCloseRequest = _ => {
          loading.value = true
          editorManager.send(
            EditorManager.Cmd.Exit(donePromise())
          );
          fileWatchThread.interrupt();
        }
        scene = new Scene {
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
                        currentBuildProperty.value = Some(build)
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
                        if (slaveBuildProperty.value.isDefined) {
                          "並列実行を無効化する"
                        } else {
                          "並列実行を有効化する"
                        },
                      slaveBuildProperty
                    )
                    disable <==
                      Bindings.createBooleanBinding(
                        () =>
                          loading.value || currentBuildProperty.value.isEmpty,
                        loading,
                        currentBuildProperty
                      )
                    onAction = _ => {
                      currentBuildProperty.value match {
                        case Some(currentBuild) if !loading.value => {
                          slaveBuildProperty.value match {
                            case Some(slaveBuild) => {
                              loading.value = true
                              slaveBuildProperty.value = None
                              editorManager.send(
                                EditorManager.Cmd.RemoveSlave(
                                  slaveBuild.id,
                                  donePromise()
                                )
                              )
                            }
                            case None => {
                              loading.value = true
                              slaveBuildProperty.value = Some(currentBuild)
                              editorManager.send(
                                EditorManager.Cmd.AddSlave(
                                  currentBuild.id,
                                  donePromise()
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
              },
              new VBox {
                diffNodeProperty.onChange { (_, _, _) =>
                  children = diffNodeProperty.value
                }
              }
            )
          }

        }
      };
      stage.show();
    });
  }

  private def createDiffNode(build1: Build, build2: Build): Node = {
    val deletedFiles =
      build1.codes.keySet
        .diff(build2.codes.keySet)
        .toList
        .sorted
        .map { filename =>
          new Text {
            text = s"deleted: ${filename}"
          }
        };
    val addedFiles =
      build2.codes.keySet.diff(build1.codes.keySet).toList.sorted.map {
        filename =>
          new Text {
            text = s"added: ${filename}"
          }
      }

    val changedFiles =
      build1.codes.keySet.intersect(build2.codes.keySet).toList.sorted.flatMap {
        file =>
          val code1 = build1.codes(file);
          val code2 = build2.codes(file);
          val diff = DiffUtils.diff(
            code1.lines.map(_.line).asJava,
            code2.lines.map(_.line).asJava
          );
          val deltas = diff.getDeltas().asScala.toList;
          if (deltas.isEmpty) {
            None
          } else {
            Some(new VBox {
              children = Seq(
                new Text {
                  text = s"changed: ${file}"
                },
                new GridPane {
                  background = new Background(
                    Array(
                      new BackgroundFill(White, null, null)
                    )
                  )
                  columnConstraints ++= Seq(
                    new ColumnConstraints {
                      percentWidth = 50
                    },
                    new ColumnConstraints {
                      percentWidth = 50
                    }
                  )
                  var offset = 0;
                  deltas.foreach { delta =>
                    delta.getSource().getLines().asScala.zipWithIndex.foreach {
                      (line, i) =>
                        add(
                          new Pane {
                            children = Seq(new TextFlow {
                              children = Seq(new Text {
                                text = line
                              })
                            })
                            style = "-fx-background-color: #ffcccc"
                          },
                          0,
                          offset + i
                        )
                    }

                    delta.getTarget().getLines().asScala.zipWithIndex.map {
                      (line, i) =>
                        add(
                          new Pane {
                            children = Seq(new TextFlow {
                              children = Seq(new Text {
                                text = line
                              })
                            })
                            style = "-fx-background-color: #ccffcc"
                          },
                          1,
                          offset + i
                        )
                    }

                    offset += delta
                      .getSource()
                      .size()
                      .max(
                        delta.getTarget().size()
                      );
                  }
                }
              )
            })
          }
      }

    new VBox {
      children = deletedFiles ++ addedFiles ++ changedFiles
    }
  }
}
