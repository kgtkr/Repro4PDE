package seekprog.shared

import io.circe._, io.circe.generic.semiauto._

case class FrameState(
    events: List[PdeEventWrapper],
    randomSeed: Long
)

object FrameState {
  implicit val encoder: Encoder[FrameState] = deriveEncoder
  implicit val decoder: Decoder[FrameState] = deriveDecoder
}
