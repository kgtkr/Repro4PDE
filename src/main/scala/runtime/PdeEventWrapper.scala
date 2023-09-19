package net.kgtkr.seekprog.runtime;

import processing.event.MouseEvent
import processing.event.KeyEvent
import io.circe._, io.circe.generic.semiauto._

// nativeフィールドに入るシステムによって作られたイベントであることを示すマーカー
object ReproductionEvent;

object PdeMouseEventWrapper {
  implicit val encoder: Encoder[PdeMouseEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeMouseEventWrapper] = deriveDecoder

  def fromPde(e: MouseEvent): PdeMouseEventWrapper = {
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
}

case class PdeMouseEventWrapper(
    millis: Long,
    action: Int,
    modifiers: Int,
    x: Int,
    y: Int,
    button: Int,
    count: Int
) {
  def toPde(): MouseEvent = {
    new MouseEvent(
      ReproductionEvent,
      millis,
      action,
      modifiers,
      x,
      y,
      button,
      count
    )
  }
}

object PdeKeyEventWrapper {
  implicit val encoder: Encoder[PdeKeyEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeKeyEventWrapper] = deriveDecoder

  def fromPde(e: KeyEvent): PdeKeyEventWrapper = {
    PdeKeyEventWrapper(
      e.getMillis(),
      e.getAction(),
      e.getModifiers(),
      e.getKey(),
      e.getKeyCode(),
      e.isAutoRepeat()
    )
  }
}

case class PdeKeyEventWrapper(
    millis: Long,
    action: Int,
    modifiers: Int,
    key: Char,
    keyCode: Int,
    isAutoRepeat: Boolean
) {
  def toPde(): KeyEvent = {
    new KeyEvent(
      ReproductionEvent,
      millis,
      action,
      modifiers,
      key,
      keyCode,
      isAutoRepeat
    )
  }
}

object PdeEventWrapper {
  implicit val encoder: Encoder[PdeEventWrapper] = deriveEncoder
  implicit val decoder: Decoder[PdeEventWrapper] = deriveDecoder
}

enum PdeEventWrapper {
  case Mouse(e: PdeMouseEventWrapper);
  case Key(e: PdeKeyEventWrapper);
}
