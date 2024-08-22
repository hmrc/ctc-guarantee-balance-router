import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val catsVersion      = "2.9.0"
  private val catsRetryVersion = "3.1.0"
  private val bootstrapVersion = "9.3.0"

  val compile = Seq(
    "uk.gov.hmrc"      %% "bootstrap-backend-play-30"    % bootstrapVersion,
    "io.lemonlabs"     %% "scala-uri"                    % "4.0.3",
    "org.typelevel"    %% "cats-core"                    % catsVersion,
    "com.github.cb372" %% "cats-retry"                   % catsRetryVersion,
    "com.github.cb372" %% "alleycats-retry"              % catsRetryVersion,
    "uk.gov.hmrc"      %% "internal-auth-client-play-30" % "1.8.0"
  )

  val test = Seq(
    "org.scalatestplus" %% "scalacheck-1-17"         % "3.2.17.0",
    "uk.gov.hmrc"       %% "bootstrap-test-play-30"  % bootstrapVersion,
    "org.mockito"       %% "mockito-scala-scalatest" % "1.17.14",
    "org.mockito"        % "mockito-core"            % "5.9.0"
  ).map(_ % Test)
}
