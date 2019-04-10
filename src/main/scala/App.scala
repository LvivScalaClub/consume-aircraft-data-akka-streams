import java.time.Instant
import java.util.concurrent.TimeoutException

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, RunnableGraph, Sink, Source, Unzip, Zip, ZipWith}
import akka.stream.{ActorMaterializer, ActorMaterializerSettings, ClosedShape, FlowShape, Outlet, Supervision, UniformFanInShape}

import scala.concurrent.ExecutionContext

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
      *                                                                             -> broadcast1 -> al_zip0
      *                                                           unzip1 -> al_zip1 -> al_function
      */

    def requestToResponseFlow(handlerF: Handler[NotUsed], alF: (((Request, Long), Response)) => Unit) = Flow.fromGraph(GraphDSL.create() { implicit b =>
      import GraphDSL.Implicits._

      val handler = b.add(handlerF)
      val enrich = b.add(Flow[Request].map(r => (r, (r, System.currentTimeMillis()))))
      val enrichUnzipper = b.add(Unzip[Request, (Request, Long)])
      val responseBroadcast = b.add(Broadcast[Response](2))
      val alZipper =  b.add(Zip[(Request, Long), Response])
      val logger = Flow[((Request, Long), Response)].map[Unit](alF)

      enrich ~> enrichUnzipper.in
                enrichUnzipper.out0 ~> handler ~> responseBroadcast
                responseBroadcast.out(1) ~> alZipper.in1
                enrichUnzipper.out1 ~> alZipper.in0
                alZipper.out ~> logger ~> Sink.ignore

      FlowShape(enrich.in, responseBroadcast.out(0))
    })


    val routeHandler: Handler[NotUsed] = Flow[Request].map(r => r + r)
    def accessLogger(a: ((Request, Long), Response)): Unit = println(a._1._2)

    requestToResponseFlow(routeHandler, accessLogger).runWith(Source.single("req1"), Sink.foreach(println))
  }
}
