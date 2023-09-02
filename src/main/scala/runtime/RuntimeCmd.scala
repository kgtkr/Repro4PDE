package net.kgtkr.seekprog.runtime;

import java.nio.ByteBuffer
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.charset.StandardCharsets

object RuntimeCmd {
  implicit val encoder: Encoder[RuntimeCmd] = deriveEncoder
  implicit val decoder: Decoder[RuntimeCmd] = deriveDecoder

  def fromJSON(json: String): RuntimeCmd = {
    decode[RuntimeCmd](json).right.get
  }

}

enum RuntimeCmd {
  case Pause();
  case Resume();

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}
