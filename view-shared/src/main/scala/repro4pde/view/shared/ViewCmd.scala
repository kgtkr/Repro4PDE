package repro4pde.view.shared

enum ViewCmd {
  case EditorManagerEvent(event: repro4pde.view.shared.EditorManagerEvent)
  case FocusRequest()
  case FileChanged()
}
