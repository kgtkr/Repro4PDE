package repro4pde.view.shared;

import scala.concurrent.Promise

enum AppCmd {
  case EditorManagerCmd(
      cmd: repro4pde.view.shared.EditorManagerCmd,
      done: Promise[Unit]
  )
  // TODO: プロセス化したら消すかも
  case Exit()
}
