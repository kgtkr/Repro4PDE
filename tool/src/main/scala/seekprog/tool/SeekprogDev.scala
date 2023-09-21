package seekprog.boot;

class SeekprogDev() extends Seekprog() {
  override def getMenuTitle() = {
    super.getMenuTitle() + " (dev)"
  }

}
