package seekprog.app;

object Logger {
  def log(message: String): Unit = {
    if (SeekprogApp.isDebug) {
      println(s"INFO: $message")
    }
  }

  def err(e: Throwable): Unit = {
    e.printStackTrace()
  }
}
