package seekprog.app;

object Logger {
  def log(message: String): Unit = {
    if (App.isDebug) {
      println(s"INFO: $message")
    }
  }

  def err(e: Throwable): Unit = {
    e.printStackTrace()
  }
}
