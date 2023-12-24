package repro4pde.view.shared

case class Config(
    logFile: Option[String] = None,
    disableComparison: Boolean = false,
    disableAutoReload: Boolean = false,
    disableRepro: Boolean = false,
    disablePause: Boolean = false,
    disablePdeButton: Boolean = false,
    disableCloseWindow: Boolean = false
)
