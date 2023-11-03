package repro4pde.app;

object Logger {
  def log(message: String): Unit = {
    if (Repro4PDEApp.isDebug) {
      println(s"INFO: $message")
    }
  }

  def err(e: Throwable): Unit = {
    e.printStackTrace()
  }
}
