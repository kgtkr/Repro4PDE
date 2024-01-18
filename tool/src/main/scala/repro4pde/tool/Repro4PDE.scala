package repro4pde.boot;

import processing.app.tools.Tool
import processing.app.Base
import java.net.URLClassLoader
import collection.JavaConverters._
import java.net.URL
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import scala.util.chaining._
import java.lang.reflect.Method

object Repro4PDE {}

class Repro4PDE() extends Tool {
  var runMethod: Method = null

  override def getMenuTitle() = {
    "Repro4PDE"
  }

  override def init(base: Base) = {
    // toolからjava modeの各種クラスにアクセスできないので、新たにクラスローダーを作成し、そこから読み込んだAppクラスに処理を委譲する
    val javaModeLoader = base
      .getModeList()
      .asScala
      .find(_.getTitle() == "Java")
      .get
      .getClass()
      .getClassLoader();
    val toolName = this.getClass().getSimpleName();
    val libDir = Base
      .getSketchbookToolsFolder()
      .toPath()
      .resolve(
        Path.of(
          toolName,
          "tool",
          "lib"
        )
      );
    val cp = Files
      .readString(libDir.resolve("app-classpath.txt"), StandardCharsets.UTF_8)
      .split(",")
      .map(name => libDir.resolve(name.trim()).toUri().toURL())

    val appClass = URLClassLoader
      .newInstance(
        cp,
        javaModeLoader
      )
      .loadClass("repro4pde.app.Repro4PDEApp")
    this.runMethod = appClass.getMethod("run");
    appClass
      .getMethod(
        "init",
        classOf[String],
        classOf[Base]
      )
      .invoke(null, toolName, base);
  }

  override def run() = {
    this.runMethod.invoke(null);
  }
}
