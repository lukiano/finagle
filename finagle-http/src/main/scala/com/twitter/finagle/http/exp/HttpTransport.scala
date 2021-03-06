package com.twitter.finagle.http.exp

import com.twitter.finagle.Status
import com.twitter.finagle.http.Message
import com.twitter.util.{Future, Promise, Return, Throw}
import scala.util.control.NonFatal

/**
 * A Transport with close behavior managed by ConnectionManager.
 *
 * @note the connection manager will close connections as required by RFC 2616 § 8
 *       irrespective of any pending requests in the dispatcher.
 */
private[finagle] class HttpTransport[A <: Message, B <: Message](self: StreamTransport[A, B], manager: ConnectionManager)
  extends StreamTransportProxy[A, B](self) {

  private[this] val readFn: Multi[B] => Unit = { case Multi(m, onFinish) =>
    manager.observeMessage(m, onFinish)
    if (manager.shouldClose)
      self.close()
  }

  def this(self: StreamTransport[A, B]) = this(self, new ConnectionManager)

  def read(): Future[Multi[B]] =
    self.read().onSuccess(readFn)

  def write(m: A): Future[Unit] =
    try {
      val p = Promise[Unit]
      manager.observeMessage(m, p)
      val f = self.write(m)
      p.become(f)
      if (manager.shouldClose) f.before(self.close())
      else f
    } catch {
      case NonFatal(e) => Future.exception(e)
    }

  override def status: Status = if (manager.shouldClose) Status.Closed else self.status
}
