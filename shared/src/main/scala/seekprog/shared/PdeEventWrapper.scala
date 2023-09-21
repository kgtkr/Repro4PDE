package seekprog.shared;

import io.circe._, io.circe.generic.semiauto._

object PdeMouseEventWrapper {
  implicit val encoder: Encoder[PdeMouseEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeMouseEventWrapper] = deriveDecoder
}

case class PdeMouseEventWrapper(
    millis: Long,
    action: Int,
    modifiers: Int,
    x: Int,
    y: Int,
    button: Int,
    count: Int
);

object PdeKeyEventWrapper {
  implicit val encoder: Encoder[PdeKeyEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeKeyEventWrapper] = deriveDecoder
}

case class PdeKeyEventWrapper(
    millis: Long,
    action: Int,
    modifiers: Int,
    key: Char,
    keyCode: Int,
    isAutoRepeat: Boolean
)
object PdeEventWrapper {
  implicit val encoder: Encoder[PdeEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeEventWrapper] = deriveDecoder
}

enum PdeEventWrapper {
  case Mouse(e: PdeMouseEventWrapper);
  case Key(e: PdeKeyEventWrapper);
}
