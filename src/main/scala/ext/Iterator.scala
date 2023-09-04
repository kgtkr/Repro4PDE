package net.kgtkr.seekprog.ext;

import scala.collection.Iterator;

extension [A](iter: Iterator[A]) {
  def mapWhile[B](f: A => Option[B]): Iterator[B] = {
    iter.map(f).takeWhile(_.isDefined).map(_.get)
  }
}
