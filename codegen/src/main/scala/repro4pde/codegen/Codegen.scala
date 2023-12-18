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

  def toDefaultValue(c: Class[?]): String = {
    if (c.isArray()) {
      "null"
    } else {
      if (c.isPrimitive()) {
        c.getName() match {
          case "boolean" => "false"
          case "byte"    => "0"
          case "char"    => "'\u0000'"
          case "short"   => "0"
          case "int"     => "0"
          case "long"    => "0L"
          case "float"   => "0.0f"
          case "double"  => "0.0d"
          case "void"    => "()"
        }
      } else {
        "null"
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
      .filter(m =>
        m.getName().endsWith("Impl")
          || m.getName() == "text"
      )
      .filter(_.getName() != "textWidthImpl")
      .map(method =>
        f"""
      override def ${method.getName()}(${method
            .getParameters()
            .map(p => s"${p.getName()}: ${toScalaType(p.getType())}")
            .mkString(", ")}) = {
        if (RuntimeMain.sketchHandler.enableDraw) {
          super.${method.getName()}(${method
            .getParameters()
            .map(p => p.getName())
            .mkString(", ")})
        } else {
          ${toDefaultValue(method.getReturnType())}
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
