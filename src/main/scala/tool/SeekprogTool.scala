package net.kgtkr.seekprog.tool;

import processing.app.tools.Tool
import processing.app.Base
import processing.mode.java.JavaEditor
import net.kgtkr.seekprog.ControlPanel

object SeekprogTool {
  var toolName: String = null
  var isDebug = false
}

class SeekprogTool(toolName: String) extends Tool {
  var base: Base = null

  override def getMenuTitle() = ???

  override def init(base: Base) = {
    this.base = base
    SeekprogTool.toolName = toolName
    SeekprogTool.isDebug = toolName == "SeekprogDev"
    ControlPanel.init()
  }

  override def run() = {
    val editor = this.base.getActiveEditor().asInstanceOf[JavaEditor]
    ControlPanel.show(editor)
  }
}
