package repro4pde.view.shared

import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum ViewCollectionEvent {
  case ViewEvent(id: Int, event: repro4pde.view.shared.ViewEvent)

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}

object ViewCollectionEvent {
  implicit val encoder: Encoder[ViewCollectionEvent] = deriveEncoder
  implicit val decoder: Decoder[ViewCollectionEvent] = deriveDecoder

  def fromJSON(json: String): ViewCollectionEvent = {
    decode[ViewCollectionEvent](json).right.get
  }
}
