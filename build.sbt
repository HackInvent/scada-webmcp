ThisBuild / organization := "io.github.hackinvent"
ThisBuild / version := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "2.13.18"
ThisBuild / javacOptions ++= Seq("--release", "17")
ThisBuild / Compile / doc / javacOptions := Seq("--release", "17", "-quiet", "-Xdoclint:all,-missing")
ThisBuild / resolvers += "HackInvent play-webmcp" at "https://raw.githubusercontent.com/HackInvent/play-webmcp/maven"

lazy val testDependencies = Seq(
  "junit" % "junit" % "4.13.2" % Test,
  "com.github.sbt" % "junit-interface" % "0.13.3" % Test,
  "org.mockito" % "mockito-core" % "5.18.0" % Test
)

lazy val core = (project in file("modules/scada-core"))
  .settings(name := "scada-core", autoScalaLibrary := false, crossPaths := false,
    libraryDependencies ++= testDependencies)

lazy val opcua = (project in file("modules/scada-opcua"))
  .dependsOn(core)
  .settings(name := "scada-opcua", autoScalaLibrary := false, crossPaths := false,
    libraryDependencies ++= Seq(
      "org.eclipse.milo" % "milo-sdk-client" % "1.1.6",
      "org.eclipse.milo" % "milo-sdk-server" % "1.1.6"
    ) ++ testDependencies,
    Test / fork := true)

lazy val playScada = (project in file("modules/play-scada"))
  .enablePlugins(PlayJava)
  .dependsOn(core, opcua)
  .settings(name := "play-scada",
    Compile / resourceGenerators += Def.task {
      val source = baseDirectory.value / "src/main/assets/play-scada.js"
      val target = (Compile / resourceManaged).value / "META-INF/resources/webjars/play-scada" / version.value / "play-scada.js"
      IO.copyFile(source, target)
      Seq(target)
    }.taskValue,
    libraryDependencies ++= Seq(guice,
      "io.github.alexusel" %% "play-webmcp" % "0.5.0") ++ testDependencies,
    Test / fork := true)

lazy val root = (project in file("."))
  .enablePlugins(PlayJava)
  .dependsOn(playScada)
  .aggregate(core, opcua, playScada)
  .settings(name := "scada-webmcp", publish / skip := true,
    libraryDependencies ++= Seq(guice) ++ testDependencies,
    Test / fork := true)
