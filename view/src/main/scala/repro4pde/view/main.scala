package repro4pde.view

import java.net.UnixDomainSocketAddress
import java.nio.channels.SocketChannel
import java.net.StandardProtocolFamily
import repro4pde.view.shared.ViewCollectionEvent
import java.util.concurrent.LinkedTransferQueue
import repro4pde.view.shared.ViewCollectionCmd
import java.io.BufferedReader
import java.io.InputStreamReader
import java.nio.channels.Channels
import java.nio.charset.StandardCharsets
import scala.collection.mutable.{Map => MMap}
import repro4pde.view.shared.ViewEvent
import scalafx.application.Platform

@main def main(sockPath: String, language: String) = {
  Platform.implicitExit = false;
  Platform.startup(() => {
    val sc = {
      val sockAddr = UnixDomainSocketAddress.of(sockPath);
      val sc = SocketChannel.open(StandardProtocolFamily.UNIX);
      sc.connect(sockAddr);
      sc
    };
    val locale = Locale.getLocale(language);
    val views = MMap[Int, View]();
    val eventQueue = new LinkedTransferQueue[ViewCollectionEvent]();

    new Thread(() => {
      for (
        event <- Iterator
          .continually {
            eventQueue.take()
          }
      ) {
        event match {
          case ViewCollectionEvent.ViewEvent(id, ViewEvent.Exit()) => {
            views.synchronized {
              views.remove(id);
            }
          }
          case _ => ()
        }

        val bytes = event.toBytes();
        sc.write(bytes);
      }
    }).start();

    new Thread(() => {
      val bs = new BufferedReader(
        new InputStreamReader(
          Channels.newInputStream(sc),
          StandardCharsets.UTF_8
        )
      );

      for (
        line <- Iterator
          .continually {
            bs.readLine()
          }
      ) {
        val cmd =
          ViewCollectionCmd.fromJSON(line);

          cmd match {
            case ViewCollectionCmd.ViewCmd(id, cmd) => {
              val view = views.synchronized {
                views(id)
              }
              view.handleCmd(cmd);
            }
            case ViewCollectionCmd.Create(id, config) => {
              val view = View(config, locale);
              view.listen { event =>
                eventQueue.add(ViewCollectionEvent.ViewEvent(id, event));
              }
              view.start();
              views.synchronized {
                views(id) = view;
              }
            }
          }
      }
    }).start();
  });

}
