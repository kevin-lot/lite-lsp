// Gatling load test of the playground API: a reading sample, not built by `make check`.
name := "playground-loadtest"
scalaVersion := "2.13.16"

val gatlingVersion = "3.13.5"

enablePlugins(GatlingPlugin)
libraryDependencies ++= Seq(
  "io.gatling.highcharts" % "gatling-charts-highcharts" % gatlingVersion % Test,
  "io.gatling" % "gatling-test-framework" % gatlingVersion % Test
)
