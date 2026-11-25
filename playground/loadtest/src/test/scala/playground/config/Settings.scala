package playground.config

import scala.concurrent.duration._

/** Where and how hard the simulation hits: system properties with defaults. */
object Settings {
  val baseUrl: String = sys.props.getOrElse("baseUrl", "http://localhost:8080")
  val users: Int = sys.props.getOrElse("users", "10").toInt
  val rampUp: FiniteDuration = 30.seconds

  def url(path: String): String = s"$baseUrl$path"
}
