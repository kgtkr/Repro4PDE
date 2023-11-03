package repro4pde.codegen;

import processing.core.PGraphics
import java.nio.file.Files
import java.nio.file.Paths

@main def Codegen(dirPath: String) = {
  val className = "PGraphicsJava2DDummyImpl";

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

  val src = f"""
    package repro4pde.runtime;

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

  val dir = Paths.get(dirPath);
  val file = dir.resolve(s"${className}.scala");
  Files.createDirectories(dir);
  Files.write(
    file,
    src.getBytes()
  );
}
