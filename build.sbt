name := "bloopers"

version := "0.1"

scalaVersion := "2.12.4"


testFrameworks += new TestFramework("utest.runner.Framework")

resolvers += Resolver.bintrayRepo("scalameta", "maven")
resolvers += Resolver.bintrayRepo("scalacenter", "releases")

updateOptions := updateOptions.value.withCachedResolution(true)

val log4jVersion = "2.8.1"
libraryDependencies ++= List(
  "ch.epfl.scala" %% "bsp" % "03e9b72d",
//  "org.scalameta" %% "lsp4s" % "095b7f87",
  "com.lihaoyi" %% "utest" % "0.6.0" % "test",
  "org.scala-sbt.ipcsocket" % "ipcsocket" % "1.0.0",
  "io.monix" %% "monix" % "2.3.3",
  "org.apache.logging.log4j" % "log4j-api" % log4jVersion,
  "org.apache.logging.log4j" % "log4j-core" % log4jVersion
)
