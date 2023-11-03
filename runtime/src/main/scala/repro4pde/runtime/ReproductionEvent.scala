package repro4pde.runtime

import repro4pde.shared.PdeMouseEventWrapper
import processing.event.Event
import repro4pde.shared.PdeKeyEventWrapper
import processing.event.MouseEvent
import processing.event.KeyEvent

// nativeフィールドに入るシステムによって作られたイベントであることを示すマーカー
object ReproductionEvent {
  def mouseEventFromPde(e: MouseEvent): PdeMouseEventWrapper = {
    PdeMouseEventWrapper(
      e.getMillis(),
      e.getAction(),
      e.getModifiers(),
      e.getX(),
      e.getY(),
      e.getButton(),
      e.getCount()
    )
  }

  def mouseEventToPde(e: PdeMouseEventWrapper): MouseEvent = {
    new MouseEvent(
      ReproductionEvent,
      e.millis,
      e.action,
      e.modifiers,
      e.x,
      e.y,
      e.button,
      e.count
    )
  }

  def keyEventFromPde(e: KeyEvent): PdeKeyEventWrapper = {
    PdeKeyEventWrapper(
      e.getMillis(),
      e.getAction(),
      e.getModifiers(),
      e.getKey(),
      e.getKeyCode(),
      e.isAutoRepeat()
    )
  }

  def keyEventToPde(e: PdeKeyEventWrapper): KeyEvent = {
    new KeyEvent(
      ReproductionEvent,
      e.millis,
      e.action,
      e.modifiers,
      e.key,
      e.keyCode,
      e.isAutoRepeat
    )
  }

  def isReproductionEvent(e: Event): Boolean = {
    e.getNative() eq ReproductionEvent
  }
}
