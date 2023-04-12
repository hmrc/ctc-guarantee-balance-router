import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val catsVersion      = "2.9.0"
  private val catsRetryVersion = "3.1.0"
  private val bootstrapVersion = "7.12.0"

  val compile = Seq(
    "uk.gov.hmrc"      %% "bootstrap-backend-play-28"    % bootstrapVersion,
    "io.lemonlabs"     %% "scala-uri"                    % "3.6.0",
    "org.typelevel"    %% "cats-core"                    % catsVersion,
    "com.github.cb372" %% "cats-retry"                   % catsRetryVersion,
    "com.github.cb372" %% "alleycats-retry"              % catsRetryVersion,
    "uk.gov.hmrc"      %% "internal-auth-client-play-28" % "1.4.0"
  )

  val test = Seq(
    "org.scalatest"       %% "scalatest"               % "3.2.14",
    "org.scalatestplus"   %% "scalacheck-1-15"         % "3.2.11.0",
    "uk.gov.hmrc"         %% "bootstrap-test-play-28"  % bootstrapVersion,
    "org.mockito"         %% "mockito-scala-scalatest" % "1.17.12",
    "org.mockito"          % "mockito-core"            % "3.12.4",
    "com.vladsch.flexmark" % "flexmark-all"            % "0.62.2"
  ).map(_ % "test, it")
}
