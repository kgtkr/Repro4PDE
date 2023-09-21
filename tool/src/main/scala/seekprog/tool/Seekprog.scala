package seekprog.boot;

import processing.app.tools.Tool
import processing.app.Base
import java.net.URLClassLoader
import collection.JavaConverters._
import java.net.URL
import java.nio.file.Path
import java.nio.file.Files
import java.nio.charset.StandardCharsets
import scala.util.chaining._

object Seekprog {
  def filterCpUrls(urls: Array[URL]) = {
    val allPlatforms = Seq(
      "linux",
      "linux-aarch64",
      "mac-aarch64",
      "mac",
      "win"
    );

    val osName = System.getProperty("os.name").toLowerCase();
    val osArch = System.getProperty("os.arch").toLowerCase();

    val platformOs = if (osName.startsWith("linux")) {
      "linux"
    } else if (osName.startsWith("mac")) {
      "mac"
    } else if (osName.startsWith("windows")) {
      "win"
    } else {
      throw new Exception("Unsupported OS: " + osName)
    };

    val platformArch = if (osArch == "aarch64") {
      "-aarch64"
    } else if (osArch == "x86_64") {
      ""
    } else if (osArch == "amd64") {
      ""
    } else {
      throw new Exception("Unsupported arch: " + osArch)
    };

    val platform = platformOs + platformArch;

    urls.filter(url => {
      val path = url.getPath();
      val name = path.substring(path.lastIndexOf("/") + 1);
      !name.startsWith("javafx-") || !allPlatforms.exists(platform =>
        name.endsWith("-" + platform + ".jar")
      ) || name.endsWith("-" + platform + ".jar")
    })
  }
}

class Seekprog() extends Tool {
  var tool: Tool = null

  override def getMenuTitle() = {
    "Seekprog"
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
      .pipe(Seekprog.filterCpUrls)

    this.tool = URLClassLoader
      .newInstance(
        cp,
        javaModeLoader
      )
      .loadClass("seekprog.app.SeekprogTool")
      .getConstructor(classOf[String])
      .newInstance(toolName)
      .asInstanceOf[Tool]
    this.tool.init(base)
  }

  override def run() = {
    this.tool.run()
  }
}
