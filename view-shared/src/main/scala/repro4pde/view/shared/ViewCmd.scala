package repro4pde.view.shared
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._

enum ViewCmd {
  case EditorManagerEvent(event: repro4pde.view.shared.EditorManagerEvent)
  case FocusRequest()
  case FileChanged()
  case EditorManagerCmdFinished(requestId: Int, error: Option[String])
}

object ViewCmd {
  implicit val encoder: Encoder[ViewCmd] = deriveEncoder
  implicit val decoder: Decoder[ViewCmd] = deriveDecoder
}
