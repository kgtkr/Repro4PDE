package net.kgtkr.seekprog;

import net.kgtkr.seekprog.tool.SeekprogTool

object Logger {
  def log(message: String): Unit = {
    if (SeekprogTool.isDebug) {
      println(s"INFO: $message")
    }
  }

  def err(e: Throwable): Unit = {
    e.printStackTrace()
  }
}
