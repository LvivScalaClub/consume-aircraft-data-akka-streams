name := "consume-aircraft-data-akka-streams"

version := "0.1"

scalaVersion := "2.12.8"

val circeVersion = "0.11.1"

libraryDependencies ++= List(akkaDeps, circeDeps).flatten

val akkaDeps = Seq(
  "com.typesafe.akka" %% "akka-stream" % "2.5.20",
  "com.typesafe.akka" %% "akka-http"   % "10.1.7",
  "com.lightbend.akka" %% "akka-stream-alpakka-json-streaming" % "1.0-M2",
  "de.heikoseeberger" %% "akka-http-circe" % "1.24.3"
)

val circeDeps = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % circeVersion)
