name := "catalogue2-ingester-marvin2"

val AkkaVersion = "2.3.3"

libraryDependencies ++= Seq(
  "com.typesafe.akka"         %% "akka-actor"       % AkkaVersion,
  "com.typesafe.akka"         %% "akka-kernel"      % AkkaVersion,
  "com.blinkbox.books.hermes" %% "rabbitmq-ha"      % "8.1.1",
  "com.typesafe.akka"         %% "akka-testkit"     % AkkaVersion % Test
)
