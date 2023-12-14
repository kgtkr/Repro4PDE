package repro4pde.app

import java.io.File
import java.util.Properties
import java.io.FileInputStream
import java.text.SimpleDateFormat
import java.util.Date
import scala.util.Random
import io.circe._, io.circe.generic.semiauto._, io.circe.syntax._

case class LogEntry(
    val timestamp: Long,
    val payload: LogPayload
) {}

object LogEntry {
  implicit val encoder: Encoder[LogEntry] = deriveEncoder
  implicit val decoder: Decoder[LogEntry] = deriveDecoder
}

enum LogPayload {
  case Init()
  case Start(sources: List[(String, String)])
  case Stop()
  case CompileError(message: String, sources: List[(String, String)])
}

object LogPayload {
  implicit val encoder: Encoder[LogPayload] = deriveEncoder
  implicit val decoder: Decoder[LogPayload] = deriveDecoder
}

// for research experiments
case class Config(
    logFile: Option[File] = None,
    disableComparison: Boolean = false,
    disableAutoReload: Boolean = false,
    disableRepro: Boolean = false,
    disablePause: Boolean = false,
    disablePdeButton: Boolean = false,
    disableCloseWindow: Boolean = false
) {
  def log(payload: => LogPayload): Unit = {
    logFile match {
      case Some(file) => {
        val writer = new java.io.FileWriter(file, true)
        writer.write(
          LogEntry(
            System.currentTimeMillis(),
            payload
          ).asJson.noSpaces + "\n"
        )
        writer.close()
      }
      case None => {}
    }
  }
}

object Config {
  def loadConfig(base: File): Config = {
    val appBase = new File(base, ".repro4pde")
    val configFile = new File(appBase, "repro4pde.properties")
    if (!configFile.exists()) {
      return Config();
    }

    val properties = new Properties()
    properties.load(new FileInputStream(configFile));
    val config = Config(
      logFile = if (properties.getProperty("logging", "false").toBoolean) {
        val timestamp = SimpleDateFormat("yyyyMMddHHmmssSSS").format(new Date())
        val random = Random.alphanumeric.take(4).mkString
        Some(new File(appBase, s"repro4pde-$timestamp-$random.log"))
      } else {
        None
      },
      disableComparison =
        properties.getProperty("disableComparison", "false").toBoolean,
      disableAutoReload =
        properties.getProperty("disableAutoReload", "false").toBoolean,
      disableRepro = properties.getProperty("disableRepro", "false").toBoolean,
      disablePause = properties.getProperty("disablePause", "false").toBoolean,
      disablePdeButton =
        properties.getProperty("disablePdeButton", "false").toBoolean,
      disableCloseWindow =
        properties.getProperty("disableCloseWindow", "false").toBoolean
    );
    config.log(LogPayload.Init())
    config
  }
}
