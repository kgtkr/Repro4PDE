package repro4pde.ui.shared;

enum EditorManagerCmd {
  case ReloadSketch(force: Boolean)
  case UpdateLocation(
      frameCount: Int
  )
  case StartSketch()
  case PauseSketch()
  case ResumeSketch()
  case StopSketch()
  case Exit()
  case AddSlave(id: Int)
  case RemoveSlave(id: Int)
  case RegenerateState()
}

enum EditorManagerEvent {
  case UpdateLocation(frameCount: Int, max: Int);
  case Stopped(playing: Boolean);
  case CreatedBuild(build: Build);
  case ClearLog();
  case LogError(slaveId: Option[Int], error: String);
  case AddedScreenshots(screenshotPaths: Map[Int, String]);
  case ClearedScreenshots();
}
