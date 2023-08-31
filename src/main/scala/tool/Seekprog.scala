package net.kgtkr.seekprog.tool;

import processing.app.tools.Tool
import processing.app.Base
import java.net.URLClassLoader
import collection.JavaConverters._

class Seekprog() extends Tool {
  var tool: Tool = null

  override def getMenuTitle() = {
    "Seekprog"
  }

  override def init(base: Base) = {
    // toolからjava modeの各種クラスにアクセスできないので、新たにクラスローダーを作成し、そこから読み込んだSeekprogToolクラスに処理を委譲する
    val toolLoader =
      this.getClass().getClassLoader().asInstanceOf[URLClassLoader]
    val javaModeLoader = base
      .getModeList()
      .asScala
      .find(_.getTitle() == "Java")
      .get
      .getClass()
      .getClassLoader()
      .asInstanceOf[URLClassLoader];
    this.tool = URLClassLoader
      .newInstance(
        toolLoader.getURLs(),
        javaModeLoader
      )
      .loadClass(classOf[Seekprog].getName() + "Tool")
      .newInstance()
      .asInstanceOf[Tool]
    this.tool.init(base)
  }

  override def run() = {
    this.tool.run()
  }
}
