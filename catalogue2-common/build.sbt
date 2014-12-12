name := "catalogue2-common"

libraryDependencies ++= Seq(
  "com.blinkbox.books"        %% "common-config"       % "1.4.1",
  "com.blinkbox.books"        %% "common-json"         % "0.2.3",
  "com.sksamuel.elastic4s"    %% "elastic4s"           % "1.4.0",
  "com.blinkbox.books"        %% "common-spray"        % "0.17.5",
  "io.spray"                  %% "spray-testkit"       % "1.3.2" % Test,
  "com.blinkbox.books"        %% "common-scala-test"   % "0.3.0" % Test,
  "com.blinkbox.books.hermes" %% "message-schemas"     % "0.7.0"
)
