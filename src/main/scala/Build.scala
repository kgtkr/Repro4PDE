package net.kgtkr.seekprog;

import processing.mode.java.JavaBuild
import java.awt.Color

class Build(
    val id: Int,
    val javaBuild: JavaBuild,
    val codes: Map[String, BuildCode]
) {}

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
    val color: Color
)
