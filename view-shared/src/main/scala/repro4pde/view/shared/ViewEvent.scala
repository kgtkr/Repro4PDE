package repro4pde.view.shared;

import io.circe._, io.circe.generic.semiauto._

enum ViewEvent {
  case EditorManagerCmd(
      cmd: repro4pde.view.shared.EditorManagerCmd,
      requestId: Int
  )
  case Exit()
}

object ViewEvent {
  implicit val encoder: Encoder[ViewEvent] = deriveEncoder
  implicit val decoder: Decoder[ViewEvent] = deriveDecoder
}
