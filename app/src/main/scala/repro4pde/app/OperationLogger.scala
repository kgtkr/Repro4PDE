package repro4pde.app

import java.io.File
import io.circe._, io.circe.generic.semiauto._, io.circe.syntax._

// for research experiments
object OperationLogger {
  case class Entry(
      val timestamp: Long,
      val payload: Payload
  ) {}

  object Entry {
    implicit val encoder: Encoder[Entry] = deriveEncoder
    implicit val decoder: Decoder[Entry] = deriveDecoder
  }

  enum Payload {
    case Init()
    case Start(sources: List[(String, String)])
    case Stop()
    case CompileError(message: String, sources: List[(String, String)])
  }

  object Payload {
    implicit val encoder: Encoder[Payload] = deriveEncoder
    implicit val decoder: Decoder[Payload] = deriveDecoder
  }

}

class OperationLogger(logFile: Option[File]) {
  def log(payload: => OperationLogger.Payload): Unit = {
    logFile match {
      case Some(file) => {
        val writer = new java.io.FileWriter(file, true)
        writer.write(
          OperationLogger
            .Entry(
              System.currentTimeMillis(),
              payload
            )
            .asJson
            .noSpaces + "\n"
        )
        writer.close()
      }
      case None => {}
    }
  }
}
