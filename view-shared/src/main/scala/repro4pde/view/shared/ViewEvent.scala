package repro4pde.view.shared;

import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

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
