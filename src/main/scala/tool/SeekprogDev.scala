package net.kgtkr.seekprog.tool;

class SeekprogDev() extends Seekprog() {
  override def getMenuTitle() = {
    super.getMenuTitle() + " (dev)"
  }

}
