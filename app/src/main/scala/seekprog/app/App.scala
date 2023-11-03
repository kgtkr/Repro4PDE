package repro4pde.app;

import processing.app.Base
import processing.mode.java.JavaEditor

object Repro4PDEApp {
  var toolName: String = null
  var isDebug = false
  var base: Base = null

  def init(toolName: String, base: Base) = {
    this.base = base
    Repro4PDEApp.toolName = toolName
    Repro4PDEApp.isDebug = toolName == "Repro4PDEDev"
    ControlPanel.init()
  }

  def run() = {
    val editor = this.base.getActiveEditor().asInstanceOf[JavaEditor]
    if (editor.getSketch().isUntitled()) {
      editor.statusError("Repro4PDE not support untitled sketch")
    } else {
      ControlPanel.show(editor)
    }
  }
}
