package repro4pde.view.shared
import io.circe._, io.circe.generic.semiauto._, io.circe.parser._,
  io.circe.syntax._
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets

enum ViewCmd {
  case EditorManagerEvent(event: repro4pde.view.shared.EditorManagerEvent)
  case FocusRequest()
  case FileChanged()
  case EditorManagerCmdFinished(requestId: Int, error: Option[String])

  def toBytes(): ByteBuffer = {
    ByteBuffer.wrap(
      (this.asJson.noSpaces + "\n").getBytes(StandardCharsets.UTF_8)
    )
  }
}

object ViewCmd {
  implicit val encoder: Encoder[ViewCmd] = deriveEncoder
  implicit val decoder: Decoder[ViewCmd] = deriveDecoder

  def fromJSON(json: String): ViewCmd = {
    decode[ViewCmd](json).right.get
  }
}
