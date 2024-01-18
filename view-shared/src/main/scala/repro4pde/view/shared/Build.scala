package repro4pde.view.shared;
import io.circe._, io.circe.generic.semiauto._

case class Build(
    id: Int,
    codes: Map[String, BuildCode]
);

object Build {
  implicit val encoder: Encoder[Build] = deriveEncoder
  implicit val decoder: Decoder[Build] = deriveDecoder
}

case class BuildCode(
    val name: String,
    val lines: List[BuildCodeLine]
);

object BuildCode {
  implicit val encoder: Encoder[BuildCode] = deriveEncoder
  implicit val decoder: Decoder[BuildCode] = deriveDecoder
}

case class BuildCodeLine(
    val number: Int,
    val line: String,
    val tokens: List[BuildCodeToken]
)

object BuildCodeLine {
  implicit val encoder: Encoder[BuildCodeLine] = deriveEncoder
  implicit val decoder: Decoder[BuildCodeLine] = deriveDecoder
}

case class BuildCodeToken(
    val token: String,
    val color: (Int, Int, Int),
    val bold: Boolean
)

object BuildCodeToken {
  implicit val encoder: Encoder[BuildCodeToken] = deriveEncoder
  implicit val decoder: Decoder[BuildCodeToken] = deriveDecoder
}
