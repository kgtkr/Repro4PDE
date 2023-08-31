package net.kgtkr.seekprog.tool;

import processing.app.tools.Tool
import processing.app.Base
import processing.mode.java.JavaEditor

class SeekprogTool() extends Tool {
  var base: Base = null

  override def getMenuTitle() = ???

  override def init(base: Base) = {
    this.base = base
  }

  override def run() = {
    val editor = this.base.getActiveEditor().asInstanceOf[JavaEditor]
    println(editor)
  }
}
