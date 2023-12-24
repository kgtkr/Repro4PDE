package repro4pde.view.shared;

import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum AppCmd {
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

object AppCmd {
  implicit val encoder: Encoder[AppCmd] = deriveEncoder
  implicit val decoder: Decoder[AppCmd] = deriveDecoder

  def fromJSON(json: String): AppCmd = {
    decode[AppCmd](json).right.get
  }
}
