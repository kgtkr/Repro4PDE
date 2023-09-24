package seekprog.app;

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
import seekprog.utils.ext._;
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
import scalafx.scene.layout.BorderPane
import scalafx.scene.control.ScrollPane
import scalafx.geometry.Pos
import scalafx.scene.layout.Priority
import scalafx.scene.layout.Region

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
    val diffNodeProperty = ObjectProperty[Region](new VBox());

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
        scene = new Scene(600, 300) {
          fill = Color.rgb(240, 240, 240)
          content = new BorderPane {
            prefHeight <== scene.height
            prefWidth <== scene.width
            style = "-fx-font: normal bold 10pt sans-serif"
            padding = Insets(50, 80, 50, 80)
            center = new VBox {
              children = Seq(
                new VBox {
                  children = Seq(
                    new HBox {
                      alignment = Pos.Center
                      val slider = new Slider(0, 0, 0) {
                        disable <== loading
                        valueChanging.addListener({
                          (_, oldChanging, changing) =>
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
                      alignment = Pos.Center
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
                                    slaveBuildProperty.value =
                                      Some(currentBuild)
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
                    }
                  )
                },
                new Pane {
                  vgrow = Priority.Always
                  hgrow = Priority.Always
                  diffNodeProperty.onChange { (_, _, _) =>
                    val node = diffNodeProperty.value;
                    node.prefHeight <== height
                    node.prefWidth <== width
                    children = Seq(node)
                  }
                }
              )
            }
          }
        }
      };
      stage.show();
    });
  }

  private def createDiffNode(sourceBuild: Build, targetBuild: Build): Region = {
    val deletedFiles =
      sourceBuild.codes.keySet
        .diff(targetBuild.codes.keySet)
        .toList
        .sorted
        .map { filename =>
          new Text {
            text = s"deleted: ${filename}"
          }
        };
    val createdFiles =
      targetBuild.codes.keySet
        .diff(sourceBuild.codes.keySet)
        .toList
        .sorted
        .map { filename =>
          new Text {
            text = s"created: ${filename}"
          }
        }

    val changedFiles =
      sourceBuild.codes.keySet
        .intersect(targetBuild.codes.keySet)
        .toList
        .sorted
        .flatMap { file =>
          val sourceCode = sourceBuild.codes(file);
          val targetCode = targetBuild.codes(file);
          val diff = DiffUtils.diff(
            sourceCode.lines.map(_.line).asJava,
            targetCode.lines.map(_.line).asJava
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
                  enum ChangeType(
                      val backgroundColor: String,
                      val marker: String
                  ) {
                    case Added extends ChangeType("#ccffcc", "+");
                    case Removed extends ChangeType("#ffcccc", "-");
                    case Unchanged extends ChangeType("#ffffff", "");
                  }

                  val sourceColOffset = 0;
                  val targetColOffset = 3;

                  def addCode(
                      colOffset: Int,
                      line: BuildCodeLine,
                      rowIndex: Int,
                      changeType: ChangeType
                  ) = {
                    add(
                      new Pane {
                        children = Seq(
                          new TextFlow {
                            children = Seq(new Text {
                              text = (line.number + 1).toString()
                            })
                          }
                        )
                        style =
                          s"-fx-background-color: ${changeType.backgroundColor}"
                      },
                      colOffset,
                      rowIndex
                    )
                    add(
                      new Pane {
                        children = Seq(new TextFlow {
                          children = Seq(new Text {
                            text = changeType.marker
                          })
                        })
                        style =
                          s"-fx-background-color: ${changeType.backgroundColor}"
                      },
                      colOffset + 1,
                      rowIndex
                    )
                    add(
                      new Pane {
                        children = Seq(new TextFlow {
                          children = line.tokens.map(token =>
                            new Text {
                              text = token.token
                              fill = Color.rgb(
                                token.color.getRed(),
                                token.color.getGreen(),
                                token.color.getBlue()
                              )
                              if (token.bold) {
                                style = "-fx-font-weight: bold"
                              }
                            }
                          )
                        })
                        style =
                          s"-fx-background-color: ${changeType.backgroundColor}"
                      },
                      colOffset + 2,
                      rowIndex
                    )
                  }

                  background = new Background(
                    Array(
                      new BackgroundFill(White, null, null)
                    )
                  )
                  columnConstraints ++= Seq(
                    new ColumnConstraints(15),
                    new ColumnConstraints(15),
                    new ColumnConstraints {
                      hgrow = Priority.Always
                    },
                    new ColumnConstraints(15),
                    new ColumnConstraints(15),
                    new ColumnConstraints {
                      hgrow = Priority.Always
                    }
                  )
                  val contextSize = 2;
                  var rowOffset = 0;

                  // これ+1はすでに表示済みなのでcontextとして表示してはいけない
                  var usedSourceLine = 0;
                  var usedTargetLine = 0;
                  deltas
                    .sortBy(_.getSource().getPosition())
                    .foreach { delta =>
                      val source = delta.getSource();
                      val target = delta.getTarget();
                      val sourceStart = source.getPosition();
                      val targetStart = target.getPosition();
                      val sourceEnd = sourceStart + source.size();
                      val targetEnd = targetStart + target.size();

                      val sourceContextSize =
                        contextSize.min(sourceStart - usedSourceLine);
                      val targetContextSize =
                        contextSize.min(targetStart - usedTargetLine);
                      val maxContextSize =
                        sourceContextSize.max(targetContextSize);

                      if (
                        usedSourceLine < sourceStart - sourceContextSize || usedTargetLine < targetStart - targetContextSize
                      ) {
                        add(
                          new TextFlow {
                            children = Seq(new Text {
                              text = "..."
                              fill = Gray
                            })

                          },
                          sourceColOffset + 2,
                          rowOffset
                        )
                        rowOffset += 1;
                      }

                      (sourceStart - sourceContextSize until sourceEnd)
                        .zip(
                          Iterator.from(
                            rowOffset + maxContextSize - sourceContextSize
                          )
                        )
                        .foreach { (line, offset) =>
                          addCode(
                            sourceColOffset,
                            sourceCode.lines(line),
                            offset,
                            if (line < sourceStart) {
                              ChangeType.Unchanged
                            } else {
                              ChangeType.Removed
                            }
                          )
                        }

                      (targetStart - targetContextSize until targetEnd)
                        .zip(
                          Iterator.from(
                            rowOffset + maxContextSize - targetContextSize
                          )
                        )
                        .foreach { (line, offset) =>
                          addCode(
                            targetColOffset,
                            targetCode.lines(line),
                            offset,
                            if (line < targetStart) {
                              ChangeType.Unchanged
                            } else {
                              ChangeType.Added
                            }
                          )
                        }

                      rowOffset += maxContextSize + target
                        .size()
                        .max(source.size());
                      usedSourceLine = sourceEnd;
                      usedTargetLine = targetEnd;
                    }

                  for (
                    (line, offset) <-
                      (usedSourceLine until sourceCode.lines.size)
                        .take(contextSize)
                        .zip(
                          Iterator.from(rowOffset)
                        )
                  ) {
                    addCode(
                      sourceColOffset,
                      sourceCode.lines(line),
                      offset,
                      ChangeType.Unchanged
                    )
                  }

                  for (
                    (line, offset) <-
                      (usedTargetLine until targetCode.lines.size)
                        .take(contextSize)
                        .zip(
                          Iterator.from(rowOffset)
                        )
                  ) {
                    addCode(
                      targetColOffset,
                      targetCode.lines(line),
                      offset,
                      ChangeType.Unchanged
                    )
                  }

                }
              )
            })
          }
        }

    if (deletedFiles.isEmpty && createdFiles.isEmpty && changedFiles.isEmpty) {
      new VBox()
    } else {
      new ScrollPane {
        val sp = this
        hbarPolicy = ScrollPane.ScrollBarPolicy.Never
        content = new VBox {
          prefWidth <== sp.width
          children = deletedFiles ++ createdFiles ++ changedFiles
          style = "-fx-font: normal 10pt monospace"
        }
      }
    }
  }
}
