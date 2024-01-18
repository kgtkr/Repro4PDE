package repro4pde.view.shared;
import io.circe._, io.circe.generic.semiauto._

enum EditorManagerCmd {
  case ReloadSketch(force: Boolean)
  case UpdateLocation(
      frameCount: Int
  )
  case StartSketch()
  case PauseSketch()
  case ResumeSketch()
  case StopSketch()
  case Exit()
  case AddSlave(id: Int)
  case RemoveSlave(id: Int)
  case RegenerateState()
}

object EditorManagerCmd {
  implicit val encoder: Encoder[EditorManagerCmd] = deriveEncoder
  implicit val decoder: Decoder[EditorManagerCmd] = deriveDecoder
}

enum EditorManagerEvent {
  case UpdateLocation(frameCount: Int, max: Int);
  case Stopped(playing: Boolean);
  case CreatedBuild(build: Build);
  case ClearLog();
  case LogError(slaveId: Option[Int], error: String);
  case AddedScreenshots(screenshotPaths: Map[Int, String]);
  case ClearedScreenshots();
}

object EditorManagerEvent {
  implicit val encoder: Encoder[EditorManagerEvent] = deriveEncoder
  implicit val decoder: Decoder[EditorManagerEvent] = deriveDecoder
}
