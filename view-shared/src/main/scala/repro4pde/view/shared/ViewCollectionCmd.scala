package repro4pde.view.shared

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._

enum ViewCollectionCmd {
  case ViewCmd(id: Int, cmd: repro4pde.view.shared.ViewCmd)
  case Create(id: Int, config: Config);

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}

object ViewCollectionCmd {
  implicit val encoder: Encoder[ViewCollectionCmd] = deriveEncoder
  implicit val decoder: Decoder[ViewCollectionCmd] = deriveDecoder

  def fromJSON(json: String): ViewCollectionCmd = {
    decode[ViewCollectionCmd](json).right.get
  }
}
