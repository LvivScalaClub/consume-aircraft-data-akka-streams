import java.time.{Instant, LocalDateTime}
import java.util.concurrent.TimeoutException

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, Supervision}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{Sink, Source}
import io.circe.Json
import io.circe.generic.auto._
import io.circe.parser._
import io.circe.syntax._

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._

//https://www.adsbexchange.com/datafields/
case class AircraftState(Icao: Int, Cou: String, Alt: Int, Lat: Double, `Long`: Double, PosTime: Long, Mdl: String, Year: Int, From: String, To: String, Gnd: Boolean) {
  def altitudeInMeters: Double = Alt * 0.3048 //from feet

  def lastUpdateTime: Instant = Instant.ofEpochMilli(PosTime)

  def latitudeLongitude: (Double, Double) = (Lat, `Long`)

  def fromAirport: String = From

  def toAirport: String = To

  def isOnTheGround: Boolean = Gnd

  def yearOfManufacture: Int = Year

  def aircraftModel: String = Mdl

  def countryOfRegistration: String = Cou
}

object App {
  val apiUrl = "https://public-api.adsbexchange.com/VirtualRadar/AircraftList.json"

  /* TASK:
   * 1. Consume JSON from apiUrl every 10 seconds.
   * 2. Split it into separate rows
   * 3. Parse it into JSON
   * 4. Filter, Group By, Map/Reduce, Count the records etc.
   */
  def main(args: Array[String]): Unit = {
    val decider: Supervision.Decider = {
      case _: ArithmeticException ⇒ Supervision.Resume
      case t: TimeoutException ⇒
        println()
        println(t)
        println()
        Supervision.Resume
      case _                      ⇒ Supervision.Stop
    }

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val ec: ExecutionContext = system.dispatcher

    //https://doc.akka.io/docs/akka/current/stream/index.html?language=scala
    //https://doc.akka.io/docs/akka-http/current/index.html?language=scala
    //https://doc.akka.io/docs/alpakka/current/

    /**
      * Backpressure in action.
      *
      * throttle is backpressuring the tick.
      */
    Source
      .tick(0.seconds, 10.seconds, None)
      .mapAsync(parallelism = 1) { _ =>
        println(s"Time of request: ${LocalDateTime.now}")
        Http().singleRequest(HttpRequest(uri = Uri(apiUrl))).flatMap(_.entity.toStrict(1.second).map(_.data))
      }
      .via(
        JsonReader.select("$.acList[*]")
      )
      .map(_.utf8String)
      .map { jsonStr =>
        parse(jsonStr).getOrElse(Json.Null).as[AircraftState]
      }
      .collect {
        case Right(data) =>
          data
      }
      .filter {
        aircraftState =>
          //aircraftState.countryOfRegistration == "United Kingdom"
          aircraftState.aircraftModel.contains("737")
      }
      .throttle(elements = 10, per = 5.seconds)
      .runWith(Sink.foreach(println))
  }
}
