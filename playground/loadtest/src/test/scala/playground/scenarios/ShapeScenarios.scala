package playground.scenarios

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import playground.config.Settings

object ShapeScenarios {
  private val listShapes =
    http("List shapes")
      .get(Settings.url("/shapes"))
      .check(status is 200, jsonPath("$[0].id").saveAs("id"))

  private def shape(id: String) =
    http("One shape")
      .get(Settings.url(s"/shapes/$id"))
      .check(status.in(200, 404))

  val browse = scenario("Browse shapes")
    .exec(listShapes)
    .pause(1.second, Settings.rampUp / 10)
    .doIf(session => session("id").asOption[String].nonEmpty) {
      exec(shape("#{id}"))
    }
}
