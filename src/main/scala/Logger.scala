package net.kgtkr.seekprog;

object Logger {
  def log(message: String): Unit = {
    println(s"INFO: $message")
  }

  def err(e: Throwable): Unit = {
    e.printStackTrace()
  }
}
