package coop.rchain.rholang.interpreter

import java.nio.file.Files

import coop.rchain.models.Channel.ChannelInstance._
import coop.rchain.models.Expr.ExprInstance._
import coop.rchain.models.TaggedContinuation.TaggedCont.ParBody
import coop.rchain.models.Var.VarInstance._
import coop.rchain.models.{GPrivate => _, _}
import coop.rchain.rholang.interpreter.implicits._
import coop.rchain.rholang.interpreter.storage.implicits._
import coop.rchain.rspace.internal.{Datum, Row, WaitingContinuation}
import coop.rchain.rspace.{IStore, LMDBStore}
import monix.execution.Scheduler.Implicits.global
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.immutable.BitSet
import scala.collection.mutable.HashMap
import scala.concurrent.{Await, ExecutionException}
import scala.concurrent.duration._

trait PersistentStoreTester {
  def withTestStore[R](
      f: IStore[Channel, BindPattern, Seq[Channel], TaggedContinuation] => R): R = {
    val dbDir = Files.createTempDirectory("rchain-storage-test-")
    val store: IStore[Channel, BindPattern, Seq[Channel], TaggedContinuation] =
      LMDBStore.create[Channel, BindPattern, Seq[Channel], TaggedContinuation](dbDir,
                                                                               1024 * 1024 * 1024)
    try {
      f(store)
    } finally {
      store.close()
    }
  }
}

class ReduceSpec extends FlatSpec with Matchers with PersistentStoreTester {

  "evalExpr" should "handle simple addition" in {
    val result = withTestStore { store =>
      val reducer    = RholangOnlyDispatcher.create(store).reducer
      val addExpr    = EPlus(GInt(7), GInt(8))
      val resultTask = reducer.evalExpr(addExpr)(Env())
      Await.result(resultTask.runAsync, 3.seconds)
    }

    val expected = Seq(Expr(GInt(15)))
    result.exprs should be(expected)
  }

  "evalExpr" should "leave ground values alone" in {
    val result = withTestStore { store =>
      val reducer    = RholangOnlyDispatcher.create(store).reducer
      val groundExpr = GInt(7)
      val resultTask = reducer.evalExpr(groundExpr)(Env())
      Await.result(resultTask.runAsync, 3.seconds)
    }

    val expected = Seq(Expr(GInt(7)))
    result.exprs should be(expected)
  }

  "evalExpr" should "handle equality between arbitary processes" in {
    val result = withTestStore { store =>
      val reducer    = RholangOnlyDispatcher.create(store).reducer
      val eqExpr     = EEq(GPrivate("private_name"), GPrivate("private_name"))
      val resultTask = reducer.evalExpr(eqExpr)(Env())
      Await.result(resultTask.runAsync, 3.seconds)
    }
    val expected = Seq(Expr(GBool(true)))
    result.exprs should be(expected)
  }

  "evalExpr" should "substitute before comparison." in {
    val result = withTestStore { store =>
      val reducer    = RholangOnlyDispatcher.create(store).reducer
      val env        = Env.makeEnv(Par(), Par())
      val eqExpr     = EEq(EVar(BoundVar(0)), EVar(BoundVar(1)))
      val resultTask = reducer.evalExpr(eqExpr)(env)
      Await.result(resultTask.runAsync, 3.seconds)
    }
    val expected = Seq(Expr(GBool(true)))
    result.exprs should be(expected)
  }

  "eval of Bundle" should "evaluate contents of bundle" in {
    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val bundleSend =
        Bundle(Send(Quote(GString("channel")), List(GInt(7), GInt(8), GInt(9)), false, BitSet()))
      val interpreter = reducer
      val resultTask  = interpreter.eval(bundleSend)(Env())
      val inspectTask = for {
        _ <- resultTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }

    result should be(
      HashMap(
        List(Channel(Quote(GString("channel")))) ->
          Row(
            List(Datum[List[Channel]](List[Channel](Quote(GInt(7)), Quote(GInt(8)), Quote(GInt(9))),
                                      false)),
            List()
          )
      ))
  }

  it should "throw an error if names are used against their polarity" in {
    /* for (n <- @bundle+ { y } ) { }  -> for (n <- y) { }
     */
    val y = GString("y")
    val receive = Receive(
      Seq(ReceiveBind(Seq(Quote(Par())), Quote(Bundle(y, readFlag = false, writeFlag = true)))),
      Par())

    an[Error] should be thrownBy {
      withTestStore { store =>
        val reducer = RholangOnlyDispatcher.create(store).reducer
        val task    = reducer.eval(receive)(Env()).map(_ => store.toMap)
        try { // Await.result wraps any Exception with ExecutionException so we need to catch it, unwrap and rethrow
          Await.result(task.runAsync, 3.seconds)
        } catch {
          case boxedError: ExecutionException =>
            throw boxedError.getCause
        }
      }
    }

    /* @bundle- { x } !(7) -> x!(7)
     */
    val x = GString("channel")
    val send =
      Send(Channel(Quote(Bundle(x, writeFlag = false, readFlag = true))), Seq(Expr(GInt(7))))

    an[Error] should be thrownBy {
      withTestStore { store =>
        val reducer = RholangOnlyDispatcher.create(store).reducer
        val task    = reducer.eval(send)(Env()).map(_ => store.toMap)
        try {
          Await.result(task.runAsync, 3.seconds)
        } catch {
          case boxedError: ExecutionException =>
            throw boxedError.getCause
        }
      }
    }
  }

  "eval of Send" should "place something in the tuplespace." in {
    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val send =
        Send(Quote(GString("channel")), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
      val interpreter = reducer
      val resultTask  = interpreter.eval(send)(Env())
      val inspectTask = for {
        _ <- resultTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }

    result should be(
      HashMap(
        List(Channel(Quote(GString("channel")))) ->
          Row(
            List(Datum[List[Channel]](List[Channel](Quote(GInt(7)), Quote(GInt(8)), Quote(GInt(9))),
                                      false)),
            List()
          )
      ))
  }

  it should "verify that Bundle is writeable before sending on Bundle " in {
    /* @bundle+ { x } !(7) -> x!(7)
     */
    val x = GString("channel")
    val send =
      Send(Channel(Quote(Bundle(x, writeFlag = true, readFlag = false))), Seq(Expr(GInt(7))))

    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val task    = reducer.eval(send)(Env()).map(_ => store.toMap)
      Await.result(task.runAsync, 3.seconds)
    }

    result should be(
      HashMap(List(Channel(Quote(x))) ->
        Row(List(Datum[List[Channel]](List[Channel](Quote(GInt(7))), false)), List())))
  }

  "eval of single channel Receive" should "place something in the tuplespace." in {
    val result = withTestStore { store =>
      val receive =
        Receive(Seq(
                  ReceiveBind(Seq(ChanVar(FreeVar(0)), ChanVar(FreeVar(1)), ChanVar(FreeVar(2))),
                              Quote(GString("channel")))),
                Par(),
                false,
                3,
                BitSet())
      val reducer     = RholangOnlyDispatcher.create(store).reducer
      val interpreter = reducer
      val resultTask  = interpreter.eval(receive)(Env())
      val inspectTask = for {
        _ <- resultTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }

    result should be(
      HashMap(
        List(Channel(Quote(GString("channel")))) ->
          Row(
            List(),
            List(
              WaitingContinuation[BindPattern, TaggedContinuation](
                List(
                  BindPattern(List(Channel(ChanVar(FreeVar(0))),
                                   Channel(ChanVar(FreeVar(1))),
                                   Channel(ChanVar(FreeVar(2)))),
                              None)),
                TaggedContinuation(ParBody(Par())),
                false
              )
            )
          )
      ))
  }

  it should "verify that bundle is readable if receiving on Bundle" in {
    /* for (@Nil <- @bundle- { y } ) { }  -> for (n <- y) { }
     */

    val y = GString("y")
    val receive = Receive(binds = Seq(
                            ReceiveBind(
                              patterns = Seq(Quote(Par())),
                              source = Quote(Bundle(y, readFlag = true, writeFlag = false))
                            )),
                          body = Par())

    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val task    = reducer.eval(receive)(Env()).map(_ => store.toMap)
      Await.result(task.runAsync, 3.seconds)
    }

    result should be(
      HashMap(
        List(Channel(Quote(y))) ->
          Row(
            List(),
            List(
              WaitingContinuation[BindPattern, TaggedContinuation](
                List(BindPattern(List(Channel(Quote(Par()))), None)),
                TaggedContinuation(ParBody(Par())),
                false))
          )
      ))
  }

  "eval of Send | Receive" should "meet in the tuplespace and proceed." in {
    val send =
      Send(Quote(GString("channel")), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
    val receive = Receive(
      Seq(
        ReceiveBind(Seq(ChanVar(FreeVar(0)), ChanVar(FreeVar(1)), ChanVar(FreeVar(2))),
                    Quote(GString("channel")),
                    freeCount = 3)),
      Send(Quote(GString("result")), List(GString("Success")), false, BitSet()),
      false,
      3,
      BitSet()
    )
    val sendFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskSendFirst = for {
        _ <- reducer.eval(send)(Env())
        _ <- reducer.eval(receive)(Env())
      } yield store.toMap
      Await.result(inspectTaskSendFirst.runAsync, 3.seconds)
    }
    sendFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )

    val receiveFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskReceiveFirst = for {
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send)(Env())
      } yield store.toMap
      Await.result(inspectTaskReceiveFirst.runAsync, 3.seconds)
    }
    receiveFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )
  }

  "eval of Send on (7 + 8) | Receive on 15" should "meet in the tuplespace and proceed." in {
    val send =
      Send(Quote(EPlus(GInt(7), GInt(8))), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
    val receive = Receive(
      Seq(
        ReceiveBind(Seq(ChanVar(FreeVar(0)), ChanVar(FreeVar(1)), ChanVar(FreeVar(2))),
                    Quote(GInt(15)),
                    freeCount = 3)),
      Send(Quote(GString("result")), List(GString("Success")), false, BitSet()),
      false,
      3,
      BitSet()
    )

    val sendFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskSendFirst = for {
        _ <- reducer.eval(send)(Env())
        _ <- reducer.eval(receive)(Env())
      } yield store.toMap
      Await.result(inspectTaskSendFirst.runAsync, 3.seconds)
    }
    sendFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )

    val receiveFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskReceiveFirst = for {
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send)(Env())
      } yield store.toMap
      Await.result(inspectTaskReceiveFirst.runAsync, 3.seconds)
    }
    receiveFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )
  }

  "eval of Send of Receive | Receive" should "meet in the tuplespace and proceed." in {
    val simpleReceive = Receive(
      Seq(ReceiveBind(Seq(Quote(GInt(2))), Quote(GInt(2)))),
      Par(),
      false,
      0,
      BitSet()
    )
    val send =
      Send(Quote(GInt(1)), Seq[Par](simpleReceive), false, BitSet())
    val receive = Receive(
      Seq(ReceiveBind(Seq(ChanVar(FreeVar(0))), Quote(GInt(1)), freeCount = 1)),
      Eval(ChanVar(BoundVar(0))),
      false,
      1,
      BitSet()
    )

    val sendFirstResult = withTestStore { store =>
      val reducer     = RholangOnlyDispatcher.create(store).reducer
      val interpreter = reducer
      val inspectTaskSendFirst = for {
        _ <- interpreter.eval(send)(Env())
        _ <- interpreter.eval(receive)(Env())
      } yield store.toMap
      Await.result(inspectTaskSendFirst.runAsync, 3.seconds)
    }
    sendFirstResult should be(
      HashMap(
        List(Channel(Quote(GInt(2)))) ->
          Row(List(),
              List(
                WaitingContinuation[BindPattern, TaggedContinuation](
                  List(
                    BindPattern(
                      List(Quote(GInt(2)))
                    )),
                  TaggedContinuation(ParBody(Par())),
                  false)
              ))
      )
    )

    val receiveFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskReceiveFirst = for {
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send)(Env())
      } yield store.toMap
      Await.result(inspectTaskReceiveFirst.runAsync, 3.seconds)
    }
    receiveFirstResult should be(
      HashMap(
        List(Channel(Quote(GInt(2)))) ->
          Row(List(),
              List(
                WaitingContinuation[BindPattern, TaggedContinuation](
                  List(
                    BindPattern(
                      List(Quote(GInt(2)))
                    )),
                  TaggedContinuation(ParBody(Par())),
                  false)
              ))
      )
    )
  }

  "Simple match" should "capture and add to the environment." in {
    val result = withTestStore { store =>
      val pattern = Send(ChanVar(FreeVar(0)), List(GInt(7), EVar(FreeVar(1))), false, BitSet())
        .withConnectiveUsed(true)
      val sendTarget =
        Send(ChanVar(BoundVar(1)), List(GInt(7), EVar(BoundVar(0))), false, BitSet(0, 1))
      val matchTerm = Match(
        sendTarget,
        List(
          MatchCase(
            pattern,
            Send(Quote(GString("result")),
                 List(Eval(ChanVar(BoundVar(1))), EVar(BoundVar(0))),
                 false,
                 BitSet(0, 1)),
            freeCount = 2
          )),
        BitSet()
      )
      val env       = Env.makeEnv[Par](GPrivate("one"), GPrivate("zero"))
      val reducer   = RholangOnlyDispatcher.create(store).reducer
      val matchTask = reducer.eval(matchTerm)(env)
      val inspectTask = for {
        _ <- matchTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }

    result should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(
            List(
              Datum[List[Channel]](List[Channel](Quote(GPrivate("one")), Quote(GPrivate("zero"))),
                                   false)),
            List()
          )
      )
    )
  }

  "eval of Send | Send | Receive join" should "meet in the tuplespace and proceed." in {
    val send1 =
      Send(Quote(GString("channel1")), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
    val send2 =
      Send(Quote(GString("channel2")), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
    val receive = Receive(
      Seq(
        ReceiveBind(Seq(ChanVar(FreeVar(0)), ChanVar(FreeVar(1)), ChanVar(FreeVar(2))),
                    Quote(GString("channel1")),
                    freeCount = 3),
        ReceiveBind(Seq(ChanVar(FreeVar(0)), ChanVar(FreeVar(1)), ChanVar(FreeVar(2))),
                    Quote(GString("channel2")),
                    freeCount = 3)
      ),
      Send(Quote(GString("result")), List(GString("Success")), false, BitSet()),
      false,
      3,
      BitSet()
    )
    val sendFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskSendFirst = for {
        _ <- reducer.eval(send1)(Env())
        _ <- reducer.eval(send2)(Env())
        _ <- reducer.eval(receive)(Env())
      } yield store.toMap
      Await.result(inspectTaskSendFirst.runAsync, 3.seconds)
    }
    sendFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )

    val receiveFirstResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskReceiveFirst = for {
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send1)(Env())
        _ <- reducer.eval(send2)(Env())
      } yield store.toMap
      Await.result(inspectTaskReceiveFirst.runAsync, 3.seconds)
    }
    receiveFirstResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )

    val interleavedResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val inspectTaskInterleaved = for {
        _ <- reducer.eval(send1)(Env())
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send2)(Env())
      } yield store.toMap
      Await.result(inspectTaskInterleaved.runAsync, 3.seconds)
    }
    interleavedResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )
  }

  "eval of Send with remainder receive" should "capture the remainder." in {
    val send =
      Send(Quote(GString("channel")), List(GInt(7), GInt(8), GInt(9)), false, BitSet())
    val receive =
      Receive(Seq(ReceiveBind(Seq(), Quote(GString("channel")), Some(FreeVar(0)), freeCount = 1)),
              Send(Quote(GString("result")), Seq(EVar(BoundVar(0)))))

    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val task = for {
        _ <- reducer.eval(receive)(Env())
        _ <- reducer.eval(send)(Env())
      } yield store.toMap
      Await.result(task.runAsync, 3.seconds)
    }
    result should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(
                Datum[List[Channel]](List[Channel](Quote(EList(List(GInt(7), GInt(8), GInt(9))))),
                                     false)),
              List())
      )
    )
  }

  "eval of nth method" should "pick out the nth item from a list" in {
    val nthCall: Expr =
      EMethod("nth", EList(List(GInt(7), GInt(8), GInt(9), GInt(10))), List[Par](GInt(2)))
    val directResult: Par = withTestStore { store =>
      val reducer =
        RholangOnlyDispatcher.create(store).reducer.asInstanceOf[Reduce.DebruijnInterpreter]
      Await.result(reducer.evalExprToPar(nthCall)(Env()).runAsync, 3.seconds)
    }
    val expectedResult: Par = GInt(9)
    directResult should be(expectedResult)

    val nthCallEvalToSend: Expr =
      EMethod("nth",
              EList(
                List(GInt(7),
                     Send(Quote(GString("result")), List(GString("Success")), false, BitSet()),
                     GInt(9),
                     GInt(10))),
              List[Par](GInt(1)))
    val indirectResult = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val nthTask = reducer.eval(nthCallEvalToSend)(Env())
      val inspectTask = for {
        _ <- nthTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }
    indirectResult should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(List(Datum[List[Channel]](List[Channel](Quote(GString("Success"))), false)), List())
      )
    )
  }

  "eval of nth method in send position" should "change what is sent" in {
    val nthCallEvalToSend: Expr =
      EMethod("nth",
              EList(
                List(GInt(7),
                     Send(Quote(GString("result")), List(GString("Success")), false, BitSet()),
                     GInt(9),
                     GInt(10))),
              List[Par](GInt(1)))
    val send: Par =
      Send(Quote(GString("result")), List[Par](nthCallEvalToSend), false, BitSet())
    val result = withTestStore { store =>
      val reducer = RholangOnlyDispatcher.create(store).reducer
      val nthTask = reducer.eval(send)(Env())
      val inspectTask = for {
        _ <- nthTask
      } yield store.toMap
      Await.result(inspectTask.runAsync, 3.seconds)
    }
    result should be(
      HashMap(
        List(Channel(Quote(GString("result")))) ->
          Row(
            List(
              Datum[List[Channel]](
                List[Channel](
                  Quote(Send(Quote(GString("result")), List(GString("Success")), false, BitSet()))),
                false)),
            List())
      )
    )
  }
}
