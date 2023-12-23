package repro4pde.ui.shared;

case class Build(
    id: Int,
    codes: Map[String, BuildCode]
);

case class BuildCode(
    val name: String,
    val lines: List[BuildCodeLine]
);

case class BuildCodeLine(
    val number: Int,
    val line: String,
    val tokens: List[BuildCodeToken]
)

case class BuildCodeToken(
    val token: String,
    val color: (Int, Int, Int),
    val bold: Boolean
)
