package net.kgtkr.seekprog.dev;

import processing.core.PGraphics
import java.nio.file.Files
import java.nio.file.Paths

// sbt runMain Codegen
@main def Codegen() = {
  def toScalaType(c: Class[?]): String = {
    if (c.isArray()) {
      s"Array[${toScalaType(c.getComponentType())}]"
    } else {
      if (c.isPrimitive()) {
        c.getName()
          .zipWithIndex
          .map((c, i) => if (i == 0) c.toUpper else c)
          .mkString("")
      } else {
        c.getName()
      }
    }
  }

  var src = """
    // generated
    package net.kgtkr.seekprog.runtime;

    import processing.awt.PGraphicsJava2D

    class PGraphicsJava2DDummyImpl extends PGraphicsJava2D { 
    """ +
    classOf[PGraphics]
      .getDeclaredMethods()
      .toList
      .filter(_.getName().endsWith("Impl"))
      .filter(_.getName() != "textWidthImpl")
      .map(method =>
        s"""
      override def ${method.getName()}(${method
            .getParameters()
            .map(p => s"${p.getName()}: ${toScalaType(p.getType())}")
            .mkString(", ")}) = {
        if (RuntimeMain.sketchHandler.onTarget) {
          super.${method.getName()}(${method
            .getParameters()
            .map(p => p.getName())
            .mkString(", ")})
        }
      }
      """
      )
      .mkString("\n") +
    "}";

  Files.write(
    Paths.get("src/main/scala/runtime/PGraphicsJava2DDummyImpl.scala"),
    src.getBytes()
  );

}
