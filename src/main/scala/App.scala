import java.time.{Instant, LocalDateTime}
import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, ClosedShape, Supervision}
import akka.stream.alpakka.json.scaladsl.JsonReader
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Merge, RunnableGraph, Sink, Source, Zip}
import akka.util.ByteString
import io.circe.Decoder.Result
import io.circe.{Decoder, Json}
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
      case t: Throwable ⇒
        println(t)
        Supervision.Stop
    }

    implicit val system: ActorSystem = ActorSystem()
    implicit val materializer: ActorMaterializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy(decider))
    implicit val ec: ExecutionContext = system.dispatcher

    //https://doc.akka.io/docs/akka/current/stream/index.html?language=scala
    //https://doc.akka.io/docs/akka-http/current/index.html?language=scala
    //https://doc.akka.io/docs/alpakka/current/

    //Flow[]


    /*Source
      .tick(0.seconds, 10.seconds, None)
      .mapAsync(1) { _ =>
        val aircraftApiSource = Source
          .fromFuture(Http().singleRequest(HttpRequest(uri = Uri(apiUrl))).flatMap(_.entity.toStrict(3.second).map(_.data)))

        val jsonReaderFlow = JsonReader.select("$.acList[*]")
        val toUtf8StringFlow = Flow[ByteString].map(_.utf8String)
        val parseFlow = Flow[String].map(parse(_).getOrElse(Json.Null).as[AircraftState])
        val validationFlow: Flow[Result[AircraftState], AircraftState, NotUsed] = Flow[Decoder.Result[AircraftState]].collect {
          case Right(data) => data
        }

        val only737 =
          Flow[AircraftState].filter(_.aircraftModel.contains("737"))

        val totalFlow = Flow[AircraftState].fold(0L)({ case (acc, el) => acc + 1})

        aircraftApiSource
          .via(jsonReaderFlow)
          .via(toUtf8StringFlow)
          .via(parseFlow)
          .via(validationFlow)
          .alsoTo(only737.via(totalFlow).to(Sink.foreach(p => println(s"737: $p"))))
          .via(totalFlow)
          .runForeach(println)
      }.runForeach(_ => println("tick"))*/

    type Request = String
    type Response = String
    type Handler[M] = Flow[Request, Response, M]

    /**
      * req -> enrichFlow: (Request, (Request, Long)) -> unzip -> unzip0 -> handler -> broadcast0 -> HttpOut
      *                                                                             -> broadcast1 -> alzip0
      *                                                                            unzip1 -> alzip1
      */

    val g = RunnableGraph.fromGraph(GraphDSL.create() { implicit builder: GraphDSL.Builder[NotUsed] =>
      import GraphDSL.Implicits._
      val in = Source(1 to 10)
      val out = Sink.foreach[(Int, Int)](println)

      val bcast = builder.add(Broadcast[Int](2))
      //val merge = builder.add(Merge[Int](2))
      val zip = builder.add(Zip[Int, Int])

      //val f1, f2, f3, f4 = Flow[Int].map(_ + 10)
      val f1, f2, f4 = Flow[Int].map(_ + 10)
      val f3 = Flow[(Int, Int)].map(identity)

      in ~> f1 ~> bcast ~> f2 ~> zip.in0
                  bcast ~> f4 ~> zip.in1
                                 zip.out ~> f3 ~> out
      ClosedShape
    })

    g.run()
  }
}
