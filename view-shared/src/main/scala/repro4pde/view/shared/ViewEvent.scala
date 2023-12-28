package repro4pde.view.shared;

import io.circe._, io.circe.generic.semiauto._

enum ViewEvent {
  case EditorManagerCmd(
      cmd: repro4pde.view.shared.EditorManagerCmd,
      requestId: Int
  )
  // EditorManager.Exit とかぶるのでどっちか消したい
  case Exit()
}

object ViewEvent {
  implicit val encoder: Encoder[ViewEvent] = deriveEncoder
  implicit val decoder: Decoder[ViewEvent] = deriveDecoder
}
