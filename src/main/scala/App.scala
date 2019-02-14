import java.time.{Instant, LocalDateTime}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
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
    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer()
    implicit val ec: ExecutionContext = system.dispatcher

    //https://doc.akka.io/docs/akka/current/stream/index.html?language=scala
    //https://doc.akka.io/docs/akka-http/current/index.html?language=scala
    //https://doc.akka.io/docs/alpakka/current/

    Source.tick(0.seconds, 10.seconds, None).mapAsync(1) { _ =>
      println(s"Time of request: ${LocalDateTime.now}")
      Http().singleRequest(HttpRequest(uri = Uri(apiUrl)))
    }.flatMapConcat(response => response.entity.dataBytes)
      .via(
        JsonReader.select("$.acList[*]")
      )
      .map(_.utf8String)
      .map { jsonStr =>
        parse(jsonStr).getOrElse(Json.Null).as[AircraftState]
      }.collect {
      case Right(data) =>
        data
    }.filter {
      aircraftState =>
        aircraftState.countryOfRegistration == "United Kingdom"
    }.throttle(10, 5.seconds).runWith(Sink.foreach(println))
  }
}
