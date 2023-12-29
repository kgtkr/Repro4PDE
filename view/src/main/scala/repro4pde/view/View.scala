package repro4pde.view;

import repro4pde.view.shared.{ViewEvent, ViewCmd}
import scala.concurrent.Promise
import scalafx.geometry.Insets
import scalafx.scene.Scene
import scalafx.scene.layout.HBox
import scalafx.scene.paint.Color._
import scalafx.scene.paint._
import scalafx.scene.text.Text
import scalafx.Includes._
import scalafx.scene.control.Slider
import scalafx.application.Platform
import scalafx.beans.binding.Bindings
import scalafx.scene.layout.VBox
import scalafx.scene.control.Button
import scalafx.beans.property.ObjectProperty
import scalafx.beans.property.BooleanProperty
import scalafx.scene.Node
import com.github.difflib.DiffUtils
import scalafx.scene.layout.Background
import scalafx.scene.layout.BackgroundFill
import scalafx.scene.text.TextFlow
import scalafx.scene.layout.GridPane
import scalafx.scene.layout.ColumnConstraints
import scalafx.scene.layout.Pane
import scalafx.scene.layout.BorderPane
import scalafx.scene.control.ScrollPane
import scalafx.geometry.Pos
import scalafx.scene.layout.Priority
import scalafx.scene.layout.Region
import scalafx.stage.Popup
import scalafx.scene.image.ImageView
import scalafx.beans.property.DoubleProperty
import scalafx.scene.shape.SVGPath
import scalafx.scene.image.Image
import repro4pde.view.shared.Config
import scala.collection.mutable.Queue as MQueue
import scala.collection.SortedMap
import repro4pde.view.shared.{
  EditorManagerCmd,
  EditorManagerEvent,
  Build,
  BuildCodeLine
};
import scala.collection.mutable.Set as MSet
import scala.util.Success
import scala.util.Failure
import scala.jdk.CollectionConverters._
import scala.collection.mutable.{Map => MMap}
import scalafx.stage.Stage

enum PlayerState {
  case Playing;
  case Paused;
  case Stopped(nextPlaying: Boolean) // nextPlaying: reload時に自動再生される状態か
}

class View(val config: Config, val locale: Locale) {
  var requestIdCounter = 0;
  val requestIdMap = MMap[Int, Promise[Unit]]();
  var eventListeners = List[ViewEvent => Unit]();

  val loading = BooleanProperty(false);
  val queue = new MQueue[() => Unit]();
  def addQueue(f: => Unit) = {
    queue.enqueue(() => f)
    if (!loading.value) {
      nextQueue()
    }
  }
  def nextQueue() = {
    if (queue.nonEmpty) {
      val f = queue.dequeue();
      loading.value = true
      f();
    }
  }

  val playerState = ObjectProperty(PlayerState.Stopped(false));
  val sliderValueProperty = DoubleProperty(0);
  val sliderMaxProperty = DoubleProperty(0);
  val sliderValueChangingProperty = BooleanProperty(false);
  val currentBuildProperty = ObjectProperty[Option[Build]](None);
  val slaveBuildProperty = ObjectProperty[Option[Build]](None);
  val diffNodeProperty = ObjectProperty[Region](new VBox());
  val slaveErrorProperty = ObjectProperty[Option[String]](None);
  val screenshotsProperty = ObjectProperty[SortedMap[
    Int,
    Image
  ]](SortedMap.empty[Int, Image]);
  val mouseHoverSliderValueProperty =
    ObjectProperty[Option[Int]](None);

  var screenshotPaths = SortedMap.empty[Int, String]
  val screenshotXProperty = DoubleProperty(0)
  val screenshotYProperty = DoubleProperty(0)
  val loadingScreenshotValues = MSet.empty[Int]

  def donePromise(onSuccess: => Unit = {}) = {
    import scala.concurrent.ExecutionContext.Implicits.global

    val promise = Promise[Unit]();
    promise.future.onComplete(result => {
      Platform.runLater {
        loading.value = false

        result match {
          case Success(_) => {
            onSuccess
          }
          case Failure(e) => {
            // TODO: エラーの種類によってはログに残さない(ビルドエラーなど)
            System.err.println(e);
          }
        }
        nextQueue()
      }
    })
    promise
  }

  var stage: Stage = null;

  def start() = {
    Platform.runLater {
      startInner()
    }
  }

  private def startInner(): Unit = {
    val screenshotProperty =
      Bindings.createObjectBinding[Option[Image]](
        () => {
          mouseHoverSliderValueProperty.value
            .flatMap(value => {
              val nearest = findNearest(
                screenshotsProperty.value,
                value
              );
              nearest.map({
                case (_, image) => {
                  image
                }
              })
            })
        },
        mouseHoverSliderValueProperty,
        screenshotsProperty
      )

    mouseHoverSliderValueProperty.onChange { (_, _, _) =>
      mouseHoverSliderValueProperty.value
        .flatMap(value =>
          findNearest(
            screenshotPaths,
            value
          )
        )
        .foreach({
          case (nearestValue, path) => {
            if (
              !screenshotsProperty.value.contains(
                nearestValue
              ) && !loadingScreenshotValues.contains(
                nearestValue
              )
            ) {
              new Image(
                s"file:${path}",
                true
              ) {
                progress.onChange { (_, _, _) =>
                  if (progress.value == 1.0) {
                    loadingScreenshotValues -= nearestValue
                    screenshotsProperty.value =
                      screenshotsProperty.value + (nearestValue -> this)
                  }
                }
              }
              loadingScreenshotValues += nearestValue
            }
          }
        })
    }

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

    var closeClickCount = 0;
    stage = new Stage {
      title = "Repro4PDE"
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
                if (!config.disableRepro) {
                  children += new HBox {
                    alignment = Pos.Center
                    children = Seq(
                      new Slider(0, 0, 0) {
                        disable <== Bindings.createBooleanBinding(
                          () => loading.value,
                          loading
                        )
                        value <==> sliderValueProperty
                        max <==> sliderMaxProperty
                        valueChanging <==> sliderValueChangingProperty
                        onMouseMoved = e => {
                          val mouseX = if e.getX.isNaN then 0 else e.getX
                          val mouseValue =
                            (mouseX / width.value) * (max.value - min.value) + min.value
                          mouseHoverSliderValueProperty.value = Some(
                            (mouseValue * 60).toInt
                          )
                          screenshotXProperty.value = e.getScreenX
                          screenshotYProperty.value = e.getScreenY
                        }
                        onMouseExited = _ => {
                          mouseHoverSliderValueProperty.value = None
                        }
                        valueChanging.addListener({
                          (_, oldChanging, changing) =>
                            if (oldChanging && !changing && !loading.value) {
                              addQueue {
                                editorManagerCmdSend(
                                  EditorManagerCmd.UpdateLocation(
                                    (value.value * 60).toInt
                                  ),
                                  donePromise {
                                    if (
                                      playerState.value == PlayerState
                                        .Stopped(
                                          true
                                        )
                                    ) {
                                      playerState.value = PlayerState.Playing;
                                    }
                                  }
                                );
                              }
                            }
                            ()
                        })
                      },
                      new Text {
                        text <== Bindings.createStringBinding(
                          () =>
                            f"${sliderValueProperty.intValue()}%d${locale.secound}/ ${sliderMaxProperty
                                .intValue()}%d${locale.secound}",
                          sliderValueProperty,
                          sliderMaxProperty
                        )
                      }
                    )

                    new Popup {
                      content += new ImageView {
                        image <== Bindings.createObjectBinding(
                          () => {
                            screenshotProperty.value
                              .map(_.delegate)
                              .orNull
                          },
                          screenshotProperty
                        )
                        fitWidth = 150
                        preserveRatio = true
                      }
                      screenshotProperty.onChange { (_, oldV, newV) =>
                        val oldDefined = oldV.isDefined
                        val newDefined = newV.isDefined
                        if (oldDefined != newDefined) {
                          if (newDefined) {
                            this.show(
                              scene.value.getWindow,
                              screenshotXProperty.value - 75,
                              screenshotYProperty.value + 20
                            )
                          } else {
                            hide()
                          }
                        }
                      }
                      screenshotXProperty.onChange { (_, _, _) =>
                        this.setX(screenshotXProperty.value - 75)
                      }
                    };
                  }
                }
                children ++= Seq(
                  new HBox(10) {
                    alignment = Pos.Center
                    children = Seq(
                      new Button {
                        val SIZE = 25;
                        prefWidth = SIZE
                        prefHeight = SIZE
                        minWidth = SIZE
                        minHeight = SIZE
                        graphic = new SVGPath {
                          scaleX = 0.015
                          scaleY = 0.015
                          content <== Bindings.createStringBinding(
                            () =>
                              if (playerState.value == PlayerState.Playing) {
                                if (config.disablePause) {
                                  SVGResources.play
                                } else {
                                  SVGResources.pause
                                }
                              } else {
                                SVGResources.play
                              },
                            playerState
                          )
                          fill = Black
                        }
                        disable <== Bindings.createBooleanBinding(
                          () =>
                            loading.value || (config.disablePause && playerState.value == PlayerState.Playing),
                          loading,
                          playerState
                        )
                        onAction = _ => {
                          if (!loading.value) {
                            addQueue {
                              playerState.value match {
                                case PlayerState.Playing
                                    if !config.disablePause => {
                                  editorManagerCmdSend(
                                    EditorManagerCmd.PauseSketch(),
                                    donePromise {
                                      playerState.value = PlayerState.Paused;
                                    }
                                  )
                                }
                                case PlayerState.Paused => {
                                  editorManagerCmdSend(
                                    EditorManagerCmd.ResumeSketch(),
                                    donePromise {
                                      playerState.value = PlayerState.Playing;
                                    }
                                  )
                                }
                                case PlayerState.Stopped(_) => {
                                  editorManagerCmdSend(
                                    EditorManagerCmd.StartSketch(),
                                    donePromise {
                                      playerState.value = PlayerState.Playing;
                                    }
                                  )
                                }
                                case _ => {}
                              }
                            }
                          }

                        }
                      },
                      new Button {
                        val SIZE = 25;
                        prefWidth = SIZE
                        prefHeight = SIZE
                        minWidth = SIZE
                        minHeight = SIZE
                        graphic = new SVGPath {
                          scaleX = 0.015
                          scaleY = 0.015
                          content = SVGResources.stop
                          fill = Black
                        }
                        disable <== loading
                        onAction = _ => {
                          if (!loading.value) {
                            addQueue {
                              editorManagerCmdSend(
                                EditorManagerCmd.StopSketch(),
                                donePromise {
                                  playerState.value = PlayerState.Stopped(
                                    false
                                  );
                                }
                              )
                            }
                          }

                        }
                      }
                    )
                  }
                )

                if (!config.disableRepro) {
                  children += new HBox(10) {
                    alignment = Pos.Center

                    children += new Button {
                      text = locale.regenerateState
                      disable <== loading
                      onAction = _ => {
                        if (!loading.value) {
                          addQueue {
                            editorManagerCmdSend(
                              EditorManagerCmd.RegenerateState(),
                              donePromise()
                            )
                          }
                        }
                      }
                    }

                    if (!config.disableComparison) {
                      children += new Button {
                        text <== Bindings.createStringBinding(
                          () =>
                            if (slaveBuildProperty.value.isDefined) {
                              locale.disableComparison
                            } else {
                              locale.enableComparison
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
                          (
                            loading.value,
                            currentBuildProperty.value
                          ) match {
                            case (false, Some(currentBuild)) => {
                              addQueue {
                                slaveBuildProperty.value match {
                                  case Some(slaveBuild) => {
                                    slaveBuildProperty.value = None
                                    editorManagerCmdSend(
                                      EditorManagerCmd.RemoveSlave(
                                        slaveBuild.id
                                      ),
                                      donePromise()
                                    )
                                  }
                                  case None => {
                                    slaveBuildProperty.value =
                                      Some(currentBuild)
                                    editorManagerCmdSend(
                                      EditorManagerCmd.AddSlave(
                                        currentBuild.id
                                      ),
                                      donePromise()
                                    )
                                  }
                                }
                              }
                            }
                            case _ => {}
                          }

                        }
                      };
                    }
                  }
                }
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
              },
              new TextFlow {
                children = Seq(
                  new Text {
                    text <== Bindings.createStringBinding(
                      () =>
                        (
                          slaveBuildProperty.value,
                          slaveErrorProperty.value
                        ) match {
                          case (Some(_), Some(error)) => {
                            locale.slaveError + ": " + error
                          }
                          case _ => {
                            ""
                          }
                        },
                      slaveErrorProperty,
                      slaveBuildProperty
                    )
                    fill = Red
                  }
                )
              }
            )
          }
        }
      }
    };

    stage.onCloseRequest = evt => {
      if (config.disableCloseWindow && closeClickCount < 10) {
        closeClickCount += 1;
        evt.consume();
      } else {
        addQueue {
          editorManagerCmdSend(
            EditorManagerCmd.Exit(),
            donePromise()
          );
        }
        eventListeners.foreach(_(ViewEvent.Exit()));
      }
    }

    stage.show();
    stage.requestFocus();
  }

  private def createDiffNode(sourceBuild: Build, targetBuild: Build): Region = {
    val deletedFiles =
      sourceBuild.codes.keySet
        .diff(targetBuild.codes.keySet)
        .toList
        .sorted
        .map { filename =>
          new Text {
            text = s"${locale.deleted}: ${filename}"
          }
        };
    val createdFiles =
      targetBuild.codes.keySet
        .diff(sourceBuild.codes.keySet)
        .toList
        .sorted
        .map { filename =>
          new Text {
            text = s"${locale.created}: ${filename}"
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
                  text = s"${locale.changed}: ${file}"
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
                                token.color._1,
                                token.color._2,
                                token.color._3
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
      new VBox {
        children = Seq(
          new TextFlow {
            children = Seq(
              new Text {
                text = locale.unchanged
              }
            )
          }
        )
      }
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

  private def findNearest[A](
      map: SortedMap[Int, A],
      key: Int
  ): Option[(Int, A)] = {
    map.get(key) match {
      case Some(value) => {
        Some((key, value))
      }
      case None => {
        val maxBefore = map.maxBefore(key);
        val minAfter = map.minAfter(key);

        (maxBefore, minAfter) match {
          case (Some((key1, value1)), Some((key2, value2))) => {
            if (key - key1 < key2 - key) {
              Some((key1, value1))
            } else {
              Some((key2, value2))
            }
          }
          case (Some((key1, value1)), None) => {
            Some((key1, value1))
          }
          case (None, Some((key2, value2))) => {
            Some((key2, value2))
          }
          case (None, None) => {
            None
          }
        }
      }
    }
  }

  def editorManagerCmdSend(cmd: EditorManagerCmd, done: Promise[Unit]) = {
    val requestId = requestIdCounter;
    requestIdCounter += 1;
    requestIdMap.synchronized {
      requestIdMap += (requestId -> done);
    }
    eventListeners.foreach(_(ViewEvent.EditorManagerCmd(cmd, requestId)));
  }

  def listen(listener: ViewEvent => Unit) = {
    eventListeners = listener :: eventListeners;
  }

  def handleCmd(cmd: ViewCmd): Unit = {
    cmd match {
      case ViewCmd.EditorManagerEvent(event) => {
        Platform.runLater {
          event match {
            case EditorManagerEvent
                  .UpdateLocation(value2, max2) => {
              sliderMaxProperty.value = max2.toDouble / 60
              if (!sliderValueChangingProperty.value) {
                sliderValueProperty.value = value2.toDouble / 60
              }
            }
            case EditorManagerEvent.Stopped(
                  playing
                ) => {
              playerState.value = PlayerState.Stopped(playing)
            }
            case EditorManagerEvent
                  .CreatedBuild(build) => {
              currentBuildProperty.value = Some(build)
            }
            case EditorManagerEvent.ClearLog() => {
              slaveErrorProperty.value = None
            }
            case EditorManagerEvent
                  .LogError(slaveId, error) => {
              if (slaveId.isDefined) {
                slaveErrorProperty.value = Some(error)
              }
            }
            case EditorManagerEvent.AddedScreenshots(
                  added
                ) => {
              screenshotPaths ++= added
            }
            case EditorManagerEvent
                  .ClearedScreenshots() => {
              screenshotPaths = SortedMap.empty[Int, String]
            }
          }
        }
      }
      case ViewCmd.FocusRequest() => {
        Platform.runLater {
          stage.requestFocus()
        }
      }
      case ViewCmd.FileChanged() => {
        Platform.runLater {
          if (!config.disableAutoReload) {
            addQueue {
              editorManagerCmdSend(
                EditorManagerCmd.ReloadSketch(
                  false
                ),
                donePromise {
                  if (
                    playerState.value == PlayerState
                      .Stopped(
                        true
                      )
                  ) {
                    playerState.value = PlayerState.Playing;
                  }
                }
              )
            }
          }

        }
      }
      case ViewCmd.EditorManagerCmdFinished(requestId, error) => {
        val done = requestIdMap.synchronized {
          val done = requestIdMap(requestId);
          requestIdMap -= requestId
          done
        }

        done.complete(
          error match {
            case Some(error) => {
              Failure(new Exception(error))
            }
            case None => {
              Success(())
            }
          }
        )
      }
    }
  }
}
