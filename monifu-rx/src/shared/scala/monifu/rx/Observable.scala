package monifu.rx

import monifu.concurrent.atomic.Atomic
import scala.annotation.tailrec
import monifu.concurrent.cancelables._
import scala.concurrent.{ExecutionContext, Promise, Future}
import monifu.concurrent.{Scheduler, Cancelable}
import monifu.rx.observers._
import monifu.rx.internal.ObservableUtils
import scala.util.control.NonFatal


trait Observable[+A]  {
  /**
   * Function that creates the actual subscription when calling `subscribe`,
   * and that starts the stream, being meant to be overridden in custom combinators
   * or in classes implementing Observable.
   *
   * @param subscriber is both an `Observer[A]` that you can use to push items
   *                   on the stream, it's also a `CompositeCancelable` to which you
   *                   can add whatever things you want to get canceled when `cancel()`
   *                   is invoked and that can also be used to check if the subscription
   *                   has been canceled from the outside - just return it as the result
   *                   of this method, after all cancelables you want were added to it
   *
   * @return a cancelable that can be used to cancel the streaming - in general it should be
   *         the subscriber given as parameter
   */
  protected def subscribeFn(subscriber: Subscriber[A]): Cancelable

  /**
   * See section 6.5. from the Rx Design Guidelines:
   * Subscribe implementations should not throw
   */
  protected def safeSubscribeFn(subscriber: Subscriber[A]): Cancelable =
    try subscribeFn(subscriber) catch {
      case NonFatal(ex) =>
        subscriber.onError(ex)
        subscriber
    }

  final def subscribe(observer: Observer[A]): Cancelable = {
    val sub = CompositeCancelable()
    val subscriber = Subscriber(AutoDetachObserver(observer, sub), sub)
    safeSubscribeFn(subscriber)
  }

  final def subscribe(nextFn: A => Unit): Cancelable =
    subscribe(AnonymousObserver(nextFn))

  final def subscribe(nextFn: A => Unit, errorFn: Throwable => Unit): Cancelable =
    subscribe(AnonymousObserver(nextFn, errorFn))

  final def subscribe(nextFn: A => Unit, errorFn: Throwable => Unit, completedFn: () => Unit): Cancelable =
    subscribe(AnonymousObserver(nextFn, errorFn, completedFn))

  final def map[B](f: A => B): Observable[B] =
    Observable(s =>
      safeSubscribeFn(s.map(observer => new Observer[A] {
        def onError(ex: Throwable) = observer.onError(ex)
        def onCompleted() = observer.onCompleted()
        def onNext(elem: A) = observer.onNext(f(elem))
      })))

  final def flatMap[B](f: A => Observable[B]): Observable[B] =
    Observable(subscriber => {
      val refCounter = RefCountCancelable(subscriber.onCompleted())

      safeSubscribeFn(subscriber.map(observer => new Observer[A] {
        def onNext(elem: A) = {
          val refID = refCounter.acquireCancelable()
          val sub = SingleAssignmentCancelable()

          val childObserver = new Observer[B] {
            def onNext(elem: B) =
              observer.onNext(elem)

            def onError(ex: Throwable) =
            // onError, cancel everything
              try observer.onError(ex) finally subscriber.cancel()

            def onCompleted() = {
              // do resource release
              subscriber -= sub
              refID.cancel()
              sub.cancel()
            }
          }

          subscriber += sub
          sub := f(elem).safeSubscribeFn(Subscriber(childObserver))
        }

        def onError(ex: Throwable) =
          try observer.onError(ex) finally subscriber.cancel()

        def onCompleted() =
          refCounter.cancel() // triggers observer.onCompleted() when all Observables created have been finished
      }))

      subscriber
    })

  final def filter(p: A => Boolean): Observable[A] =
    Observable(s => safeSubscribeFn(s.map(observer => new Observer[A] {
      def onNext(elem: A) = if (p(elem)) observer.onNext(elem)
      def onError(ex: Throwable) = observer.onError(ex)
      def onCompleted() = observer.onCompleted()
    })))

  final def subscribeOn(s: Scheduler): Observable[A] =
    Observable { subscriber =>
      subscriber += s.schedule(_ => safeSubscribeFn(subscriber))
      subscriber
    }

  final def head: Observable[A] = take(1)
  final def tail: Observable[A] = drop(1)

  final def take(nr: Int): Observable[A] = {
    require(nr > 0, "number of elements to take should be strictly positive")

    Observable(s => safeSubscribeFn(s.map(observer => new Observer[A] {
      val count = Atomic(0)

      @tailrec
      def onNext(elem: A): Unit = {
        val currentCount = count.get

        if (currentCount < nr) {
          val newCount = currentCount + 1
          if (!count.compareAndSet(currentCount, newCount))
            onNext(elem)
          else {
            observer.onNext(elem)
            if (newCount == nr)
              observer.onCompleted()
          }
        }
      }

      def onCompleted(): Unit =
        observer.onCompleted()

      def onError(ex: Throwable): Unit =
        observer.onError(ex)
    })))
  }

  final def drop(nr: Int): Observable[A] = {
    require(nr > 0, "number of elements to drop should be strictly positive")

    Observable(s => safeSubscribeFn(s.map(observer => new Observer[A] {
      val count = Atomic(0)

      @tailrec
      def onNext(elem: A): Unit = {
        val currentCount = count.get

        if (currentCount < nr) {
          val newCount = currentCount + 1
          if (!count.compareAndSet(currentCount, newCount))
            onNext(elem)
        }
        else
          observer.onNext(elem)
      }

      def onCompleted(): Unit =
        observer.onCompleted()

      def onError(ex: Throwable): Unit =
        observer.onError(ex)
    })))
  }

  final def takeWhile(p: A => Boolean): Observable[A] =
    Observable(s => safeSubscribeFn(s.map(observer => new Observer[A] {
      val shouldContinue = Atomic(true)

      def onNext(elem: A): Unit =
        if (shouldContinue.get) {
          val update = p(elem)
          if (shouldContinue.compareAndSet(expect=true, update=update) && update)
            observer.onNext(elem)
          else if (!update)
            observer.onCompleted()
        }

      def onCompleted(): Unit =
        observer.onCompleted()

      def onError(ex: Throwable): Unit =
        observer.onError(ex)
    })))

  final def dropWhile(p: A => Boolean): Observable[A] =
    Observable(s => safeSubscribeFn(s.map(observer => new Observer[A] {
      val shouldDropRef = Atomic(true)

      @tailrec
      def onNext(elem: A): Unit =
        if (!shouldDropRef.get)
          observer.onNext(elem)
        else {
          val shouldDrop = p(elem)
          if (!shouldDropRef.compareAndSet(expect=true, update=shouldDrop) || !shouldDrop)
            onNext(elem)
        }

      def onCompleted(): Unit =
        observer.onCompleted()

      def onError(ex: Throwable): Unit =
        observer.onError(ex)
    })))

  final def foldLeft[R](initial: R)(f: (R, A) => R): Observable[R] =
    Observable { subscriber =>
      val state = Atomic(initial)

      safeSubscribeFn(subscriber.map(observer => new Observer[A] {
        def onNext(elem: A): Unit =
          state.transformAndGet(s => f(s, elem))

        def onCompleted(): Unit = {
          observer.onNext(state.get)
          observer.onCompleted()
        }

        def onError(ex: Throwable): Unit =
          observer.onError(ex)
      }))
    }

  final def ++[B >: A](other: => Observable[B]): Observable[B] =
    Observable[B](s => safeSubscribeFn(s.map(observer =>
      SynchronizedObserver(new Observer[A] {
        def onNext(elem: A): Unit = observer.onNext(elem)
        def onError(ex: Throwable): Unit = observer.onError(ex)
        def onCompleted(): Unit = other.safeSubscribeFn(s)
      }))))

  /**
   * Returns the first generated result as a Future and then cancels
   * the subscription.
   */
  final def asFuture(implicit ec: ExecutionContext): Future[Option[A]] = {
    val promise = Promise[Option[A]]()

    head.subscribe(new Observer[A] {
      def onError(ex: Throwable): Unit =
        promise.tryFailure(ex)

      def onNext(elem: A): Unit =
        promise.trySuccess(Some(elem))

      def onCompleted(): Unit =
        promise.trySuccess(None)
    })

    promise.future
  }

  final def synchronized: Observable[A] =
    Observable(s => safeSubscribeFn(s.map(SynchronizedObserver.apply)))
}

object Observable extends ObservableUtils {
  def apply[A](f: Subscriber[A] => Cancelable): Observable[A] =
    new Observable[A] {
      def subscribeFn(subscriber: Subscriber[A]) = f(subscriber)
    }
}
