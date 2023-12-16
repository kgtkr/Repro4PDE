package repro4pde.app

import java.io.File
import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import scala.util.Random

case class Config(
    logFile: Option[File] = None,
    disableComparison: Boolean = false,
    disableAutoReload: Boolean = false,
    disableRepro: Boolean = false,
    disablePause: Boolean = false,
    disablePdeButton: Boolean = false,
    disableCloseWindow: Boolean = false
) {}

object Config {
  def loadConfig(base: File): (Config, OperationLogger) = {
    val appBase = new File(base, ".repro4pde")
    val configFile = new File(appBase, "repro4pde.properties")
    val config = if (!configFile.exists()) {
      Config();
    } else {
      val properties = new Properties()
      properties.load(new FileInputStream(configFile));
      val config = Config(
        logFile = if (properties.getProperty("logging", "false").toBoolean) {
          val timestamp =
            SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date())
          val random = Random.alphanumeric.take(4).mkString
          Some(new File(appBase, s"repro4pde-$timestamp-$random.log"))
        } else {
          None
        },
        disableComparison =
          properties.getProperty("disableComparison", "false").toBoolean,
        disableAutoReload =
          properties.getProperty("disableAutoReload", "false").toBoolean,
        disableRepro =
          properties.getProperty("disableRepro", "false").toBoolean,
        disablePause =
          properties.getProperty("disablePause", "false").toBoolean,
        disablePdeButton =
          properties.getProperty("disablePdeButton", "false").toBoolean,
        disableCloseWindow =
          properties.getProperty("disableCloseWindow", "false").toBoolean
      );
      config
    }
    val logger = new OperationLogger(config.logFile)

    logger.log(OperationLogger.Payload.Init())
    (config, logger)
  }
}
