package im.tox.core.io

import com.typesafe.scalalogging.Logger
import im.tox.core.io.IO.{TimerId, NetworkEvent}
import org.scalacheck.Gen
import org.scalatest.FunSuite
import org.scalatest.prop.PropertyChecks
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.language.postfixOps
import scalaz.concurrent.Task
import scalaz.stream.{Sink, Process, async}

final class TimeActorTest extends FunSuite with PropertyChecks {

  private val logger = Logger(LoggerFactory.getLogger(getClass))

  private def performanceTest(
    useTimeActor: Boolean,
    count: Int,
    enqueue: Sink[Task, IO.Action],
    dequeue: Process[Task, IO.Action],
    close: Task[Unit]
  ): Unit = {
    val startTimer = IO.Action.StartTimer(TimerId("Start"), 100 millis, None, _ => None)
    val shutdown = IO.Action.Shutdown

    val enqueueLoop = (Process(startTimer).repeat.take(count) ++ Process.emit(shutdown)).toSource.to(enqueue)
    val dequeueLoop =
      if (useTimeActor) {
        val eventQueue = async.boundedQueue[IO.Event](10)
        TimeActor.make(dequeue, eventQueue.enqueue)
      } else {
        dequeue.flatMap { i =>
          if (i == IO.Action.Shutdown) {
            Process.eval(close)
          } else {
            Process.empty
          }
        }
      }

    val start = System.currentTimeMillis().millis
    enqueueLoop.merge(dequeueLoop).run.run
    val end = System.currentTimeMillis().millis

    val duration = end - start
    println(s"Test with count=$count took $duration (${(duration / count).toMicros} µs/element)")
  }

  test("performance of async.boundedQueue") {
    forAll(Gen.choose(100, 200)) { count =>
      val queue = async.boundedQueue[IO.Action](10)

      performanceTest(true, count, queue.enqueue, queue.dequeue, queue.close)
    }
  }

  test("performance of async.topic") {
    forAll(Gen.choose(100, 200)) { count =>
      val queue = async.topic[IO.Action]()

      performanceTest(false, count, queue.publish, queue.subscribe, queue.close)
    }
  }

  test("resetting a timer") {
    val shutdownAfter = 300 millis
    val resetAfter = 100 millis

    val actionQueue = async.boundedQueue[IO.Action](10)
    val eventQueue = async.boundedQueue[IO.Event](10)

    val timeActor = TimeActor.make(actionQueue.dequeue, eventQueue.enqueue)

    val actionActor = Process(
      // 1. Enqueue Action: set up a non-repeating (repeat = Some(1)) timer of 1 second without event.
      IO.Action.StartTimer(TimerId("Shutdown"), shutdownAfter, Some(1), { _ =>
        logger.error("Shutdown timer #1 fired")
        fail("Shutdown timer #1 should never fire")
      }),
      // 2. Enqueue Action: timer of 500ms with some NetworkEvent.
      IO.Action.StartTimer(TimerId("Reset"), resetAfter, Some(1), { _ =>
        logger.debug("Reset timer fired")
        Some(NetworkEvent(null))
      })
    ).toSource.to(actionQueue.enqueue)

    // 3. On receiving the event, reset the first timer, so it's 1 second again.
    val eventActor = eventQueue.dequeue.flatMap {
      case IO.NetworkEvent(_) =>
        logger.debug("Received NetworkEvent")
        Process(
          IO.Action.StartTimer(TimerId("Shutdown"), shutdownAfter, Some(1), { _ =>
            logger.debug("Shutdown timer #2 fired")
            Some(IO.ShutdownEvent)
          })
        ).toSource.to(actionQueue.enqueue)

      case IO.ShutdownEvent =>
        logger.debug("Received NetworkEvent")
        Process.empty

      case event =>
        fail("Unexpected event: " + event)
    }

    // 4. Run the process.
    val start = System.currentTimeMillis().millis
    timeActor
      .merge(eventActor)
      .merge(actionActor)
      .take(5)
      .run.run
    val end = System.currentTimeMillis().millis

    // 5. Assert that the whole thing took at least 1500ms.
    val duration = end - start
    logger.debug(s"Test took $duration")
    assert(duration >= shutdownAfter + resetAfter)
    assert(duration < shutdownAfter * 2)
  }

  test("cancelling timers") {
    val shutdownAfter = 300 millis
    val cancelAfter = 100 millis

    val actionQueue = async.boundedQueue[IO.Action](10)
    val eventQueue = async.boundedQueue[IO.Event](10)

    val timeActor = TimeActor.make(actionQueue.dequeue, eventQueue.enqueue)

    val actionActor = Process(
      IO.Action.StartTimer(TimerId("Shutdown"), shutdownAfter, Some(1), { _ =>
        logger.error("Shutdown timer #1 fired")
        fail("Shutdown timer #1 should never fire")
      }),
      IO.Action.StartTimer(TimerId("Cancel"), cancelAfter, Some(1), { _ =>
        logger.debug("Cancel timer fired")
        Some(IO.ShutdownEvent)
      }),
      IO.Action.StartTimer(TimerId("Real shutdown"), shutdownAfter * 2, Some(1), { _ =>
        logger.debug("Actually shutting down now")
        Some(IO.ShutdownEvent)
      })
    ).toSource.to(actionQueue.enqueue)

    val eventActor = eventQueue.dequeue.flatMap {
      case IO.ShutdownEvent =>
        logger.debug("Received ShutdownEvent")
        Process(
          IO.Action.CancelTimer(TimerId("Shutdown"))
        ).toSource.to(actionQueue.enqueue)

      case event =>
        fail("Unexpected event: " + event)
    }

    timeActor
      .merge(eventActor)
      .merge(actionActor)
      .take(6)
      .run.run
  }

  test("parallel timers") {
    val delay = 100 milliseconds
    val repeats = 1
    val timers = 4

    val actionQueue = async.boundedQueue[IO.Action](10)
    val eventQueue = async.boundedQueue[IO.Event](10)

    val shutdownTimer = IO.TimerIdFactory("Shutdown")
    val actionActor = Process.range(0, timers).map { id =>
      IO.Action.StartTimer(shutdownTimer(id.toString), delay, Some(repeats), _ => Some(IO.ShutdownEvent))
    }.toSource.to(actionQueue.enqueue)

    val timeActor = TimeActor.make(actionQueue.dequeue, eventQueue.enqueue)

    val testActor =
      eventQueue.dequeue
        .scan(0) { (count, event) =>
          logger.debug("Event: " + event)
          count + 1
        }
        .flatMap { count =>
          if (count == repeats * timers) {
            actionActor.kill
          } else {
            Process.empty[Task, Unit]
          }
        }

    val start = System.currentTimeMillis()
    actionActor
      .merge(timeActor)
      .merge(testActor)
      .run.run
    val end = System.currentTimeMillis()

    assert(end - start < (delay.toMillis * repeats * timers))
  }

}
