package net.kgtkr.seekprog.codegen;

import processing.core.PGraphics
import java.nio.file.Files
import java.nio.file.Paths
import java.io.File

@main def Codegen(rootDir: String, packageName: String, className: String) = {
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

  var src = f"""
    package ${packageName};

    import processing.awt.PGraphicsJava2D

    class ${className} extends PGraphicsJava2D { 
    """ +
    classOf[PGraphics]
      .getDeclaredMethods()
      .toList
      .filter(_.getName().endsWith("Impl"))
      .filter(_.getName() != "textWidthImpl")
      .map(method =>
        f"""
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

  val dir = Paths.get(rootDir, packageName.replace(".", File.separator));
  val file = dir.resolve(s"${className}.scala");
  Files.createDirectories(dir);
  Files.write(
    file,
    src.getBytes()
  );
}
