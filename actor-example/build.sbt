name := "actor-example"

version := "0.1"

scalaVersion := "2.13.6"

Test / testOptions += Tests.Argument(TestFrameworks.JUnit, "-a", "-v", "-s")
Test / parallelExecution := false

val akkaVersion = "2.6.0"

scalacOptions ++= Seq(
  "-feature",
  "-deprecation",
  "-encoding", "UTF-8",
  "-unchecked",
  "-Xlint",
)

libraryDependencies ++= Seq(
  "com.typesafe.akka"        %% "akka-actor"         % akkaVersion,
  "com.typesafe.akka"        %% "akka-testkit"       % akkaVersion % Test,
  "com.novocode"             % "junit-interface"     % "0.11"      % Test,
  "org.asynchttpclient"      % "async-http-client"   % "2.2.0",
  "org.jsoup"                % "jsoup"               % "1.8.1"
)