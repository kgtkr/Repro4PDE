package repro4pde.boot;

class Repro4PDEDev() extends Repro4PDE() {
  override def getMenuTitle() = {
    super.getMenuTitle() + " (dev)"
  }

}
