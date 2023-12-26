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

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}

object ViewEvent {
  implicit val encoder: Encoder[ViewEvent] = deriveEncoder
  implicit val decoder: Decoder[ViewEvent] = deriveDecoder

  def fromJSON(json: String): ViewEvent = {
    decode[ViewEvent](json).right.get
  }
}
