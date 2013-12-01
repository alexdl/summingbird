/*
Copyright 2013 Twitter, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package com.twitter.summingbird.storm

import scala.util.{Try, Success, Failure}

import com.twitter.summingbird.batch.Timestamp
import com.twitter.summingbird.storm.option.{AnchorTuples, MaxWaitingFutures}
import com.twitter.summingbird.online.executor.OperationContainer
import com.twitter.summingbird.online.executor.InputState
import org.slf4j.{LoggerFactory, Logger}

/**
 *
 * @author Oscar Boykin
 * @author Sam Ritchie
 * @author Ashu Singhal
 */
case class BaseBolt[I,O](
  hasDependants: Boolean,
  executor: OperationContainer[I, O, WireType, WireType]
  ) extends IRichBolt {


  @transient protected lazy val logger: Logger =
    LoggerFactory.getLogger(getClass)

  protected def logError(message: String, err: Throwable) {
    logger.error(message, err)
  }

 private def fail(inputs: List[InputState[WireType]], error: Throwable): Unit = {
    executor.notifyFailure(inputs, error)
    logError("Akka DAG of: %d tuples failed".format(inputs.size), error)
  }

  def processResults(d: Data) {

  }

 def receive = {
    case Tick =>
      val wrappedTuple = InputState(tuple)
      processResults(executor.execute(wrappedTuple, None))
    case w@WireType =>
      val tsIn = executor.decoder.invert(tuple.getValues).get // Failing to decode here is an ERROR
      processResults(executor.execute(wrappedTuple, Some(tsIn)))
  }

  override def execute(tuple: WireType) = {


    /**
     * System ticks come with a fixed stream id
     */
    val curResults = if(!tuple.getSourceStreamId.equals("__tick")) {
    } else {

    }
    curResults.foreach{ case (tups, res) =>
      res match {
        case Success(outs) => finish(tups, outs)
        case Failure(t) => fail(tups, t)
      }
    }
  }

  private def finish(inputs: List[InputState[Tuple]], results: TraversableOnce[(Timestamp, O)]) {
    var emitCount = 0
    if(hasDependants) {
      if(anchorTuples.anchor) {
        results.foreach { result =>
          collector.emit(inputs.map(_.state).asJava, executor.encoder(result))
          emitCount += 1
        }
      }
      else { // don't anchor
        results.foreach { result =>
          collector.emit(executor.encoder(result))
          emitCount += 1
        }
      }
    }
    // Always ack a tuple on completion:
    inputs.foreach(_.ack(collector.ack(_)))

    logger.debug("bolt finished processed {} linked tuples, emitted: {}", inputs.size, emitCount)
  }

  override def prepare(conf: JMap[_,_], context: TopologyContext, oc: OutputCollector) {
    collector = oc
    metrics().foreach { _.register(context) }
    executor.init
  }

  override def declareOutputFields(declarer: OutputFieldsDeclarer) {
    if(hasDependants) { declarer.declare(new Fields(executor.encoder.fields.asJava)) }
  }

  override val getComponentConfiguration = null

  override def cleanup {
    executor.cleanup
  }

    /** This is clearly not safe, but done to deal with GC issues since
   * storm keeps references to values
   */
  private lazy val valuesField = {
    val tupleClass = classOf[TupleImpl]
    val vf = tupleClass.getDeclaredField("values")
    vf.setAccessible(true)
    vf
  }

  private def clearValues(t: Tuple): Unit = {
    valuesField.set(t, null)
  }
}