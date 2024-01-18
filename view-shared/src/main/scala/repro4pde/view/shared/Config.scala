package repro4pde.view.shared

import io.circe._, io.circe.generic.semiauto._

case class Config(
    logFile: Option[String] = None,
    disableComparison: Boolean = false,
    disableAutoReload: Boolean = false,
    disableRepro: Boolean = false,
    disablePause: Boolean = false,
    disablePdeButton: Boolean = false,
    disableCloseWindow: Boolean = false
)

object Config {
  implicit val configDecoder: Decoder[Config] = deriveDecoder
  implicit val configEncoder: Encoder[Config] = deriveEncoder
}
