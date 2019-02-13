import java.time.{Instant, LocalDateTime}

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{Flow, Sink, Source}
import akka.stream.{ActorMaterializer, OverflowStrategy}
import io.circe.Json
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser._

import scala.concurrent.duration._

//https://www.adsbexchange.com/datafields/
case class AircraftState(Icao: Int, Cou: String, Alt: Int, Lat: Double, `Long`: Double, PosTime: Long, Mdl: String, Year: Int, From: String, To: String, Gnd: Boolean) {
  def altitudeInMeters: Double = Alt * 0.3048 //from feets

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

    Source.tick(0.seconds, 10.seconds, None)
      .mapAsync(1) { _ =>
        println(s"Consuming new file at ${LocalDateTime.now}")
        Http().singleRequest(HttpRequest(uri = apiUrl))
      }
      .flatMapConcat(_.entity.dataBytes) //get JSON payload from response as Source
      .via(JsonReader.select("$.acList[*]"))
      .map(_.utf8String)
      .map((jsonStr: String) => parse(jsonStr).getOrElse(Json.Null).as[AircraftState])
      .collect {
        case Right(data) => data
      }
      .filter(aircraftState => aircraftState.countryOfRegistration == "Taiwan")
      .to(Sink.foreach(println)).run()
  }

  def exampleWithParsingAndCounting(implicit ac: ActorSystem, am: ActorMaterializer) = {
    Source.tick(0.seconds, 20.seconds, None)
      .mapAsync(1) { _ =>
        println(s"Consuming new file at ${LocalDateTime.now}")
        Http().singleRequest(HttpRequest(uri = apiUrl))
      }
      .flatMapConcat(_.entity.dataBytes) //get JSON payload from response as Source
      .via(JsonReader.select("$.acList[*]"))
      .map(_.utf8String)
      .map((jsonStr: String) => parse(jsonStr).getOrElse(Json.Null).as[AircraftState])
      .collect {
        case Right(a) => a
      }
      .statefulMapConcat {
        () =>
          var count = 0
          str =>
            count += 1
            List((count, str))
      }.to(Sink.foreach(println)).run()
  }

  def exampleWithBackpressure(implicit ac: ActorSystem, am: ActorMaterializer) = {
    Source.tick(0.seconds, 10.seconds, None)
      .mapAsync(1)(_ => Http().singleRequest(HttpRequest(uri = apiUrl)))
      .flatMapConcat(_.entity.dataBytes) //get JSON payload from response as Source
      .via(JsonReader.select("$.acList[*]"))
      .map(_.utf8String)
      .map((jsonStr: String) => parse(jsonStr).getOrElse(Json.Null).as[AircraftState])
      .collect {
        case Right(a) => a
      }
      .statefulMapConcat {
        () =>
          var count = 0
          str =>
            count += 1
            List((count, str))
      }
      .buffer(10000, OverflowStrategy.dropHead)
      .via(Flow.fromFunction { case data@(count, str) =>
        if (count > 5000 & count < 7000) {
          println("Triggering backpressure")
          Thread.sleep(10.seconds.toMillis)
          data
        }
        else data
      })
      .to(Sink.foreach(println)).run()
  }
}
