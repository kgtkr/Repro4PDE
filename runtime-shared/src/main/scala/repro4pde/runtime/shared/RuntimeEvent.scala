package repro4pde.runtime.shared;

import java.nio.ByteBuffer
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.charset.StandardCharsets

object RuntimeEvent {
  implicit val encoder: Encoder[RuntimeEvent] = deriveEncoder
  implicit val decoder: Decoder[RuntimeEvent] = deriveDecoder

  def fromJSON(json: String): RuntimeEvent = {
    decode[RuntimeEvent](json).right.get
  }

}

enum RuntimeEvent {
  case OnTargetFrameCount(
      screenshotPaths: Map[Int, String]
  )
  case OnUpdateLocation(
      frameCount: Int,
      trimMax: Boolean,
      frameStates: List[FrameState],
      windowX: Int,
      windowY: Int,
      screenshotPath: Option[String]
  );
  case OnPaused;
  case OnResumed;

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}
