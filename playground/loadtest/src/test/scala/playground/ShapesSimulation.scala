package playground

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import playground.config.Settings._
import playground.scenarios.ShapeScenarios

class ShapesSimulation extends Simulation {
  private val protocol = http.baseUrl(baseUrl).acceptHeader("application/json")

  setUp(
    ShapeScenarios.browse.inject(rampUsers(users) during rampUp)
  ).protocols(protocol)
}
