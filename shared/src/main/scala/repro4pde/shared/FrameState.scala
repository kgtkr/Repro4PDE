package repro4pde.shared

import io.circe._, io.circe.generic.semiauto._

case class FrameState(
    events: List[PdeEventWrapper]
)

object FrameState {
  implicit val encoder: Encoder[FrameState] = deriveEncoder
  implicit val decoder: Decoder[FrameState] = deriveDecoder
}
