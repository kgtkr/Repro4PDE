package seekprog.app;

import processing.app.tools.Tool
import processing.app.Base
import processing.mode.java.JavaEditor

object SeekprogApp {
  var toolName: String = null
  var isDebug = false
  var base: Base = null

  def init(toolName: String, base: Base) = {
    this.base = base
    SeekprogApp.toolName = toolName
    SeekprogApp.isDebug = toolName == "SeekprogDev"
    ControlPanel.init()
  }

  def run() = {
    val editor = this.base.getActiveEditor().asInstanceOf[JavaEditor]
    if (editor.getSketch().isUntitled()) {
      editor.statusError("Seekprog not support untitled sketch")
    } else {
      ControlPanel.show(editor)
    }
  }
}
