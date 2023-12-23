package repro4pde.view.shared;

enum AppCmd {
  case EditorManager(cmd: EditorManagerCmd)
}

enum AppEvent {
  case EditorManager(event: EditorManagerEvent)
}
