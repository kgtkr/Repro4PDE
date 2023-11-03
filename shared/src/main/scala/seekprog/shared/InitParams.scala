package repro4pde.shared

import io.circe._, io.circe.generic.semiauto._

case class InitParams(
    targetFrameCount: Int,
    frameStates: List[FrameState],
    initPaused: Boolean,
    slaveMode: Boolean,
    isDebug: Boolean
)

object InitParams {
  implicit val encoder: Encoder[InitParams] = deriveEncoder
  implicit val decoder: Decoder[InitParams] = deriveDecoder
}
