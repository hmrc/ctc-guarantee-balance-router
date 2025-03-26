import play.sbt.routes.RoutesKeys
import uk.gov.hmrc.DefaultBuildSettings
import uk.gov.hmrc.sbtdistributables.SbtDistributablesPlugin

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "3.3.4"

lazy val microservice = Project("ctc-guarantee-balance-router", file("."))
  .enablePlugins(PlayScala, SbtDistributablesPlugin)
  .settings(
    PlayKeys.playDefaultPort := 9509,
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions += "-Wconf:src=routes/.*:s",
    RoutesKeys.routesImport ++= Seq(
      "uk.gov.hmrc.ctcguaranteebalancerouter.models._"
    )
  )
  .settings(inThisBuild(buildSettings))
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(CodeCoverageSettings.settings: _*)

lazy val it = project
  .enablePlugins(PlayScala)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(DefaultBuildSettings.itSettings())
  .settings(libraryDependencies ++= AppDependencies.test)

lazy val buildSettings = Def.settings(
  scalafmtOnCompile := true
)

lazy val scalacSettings = Def.settings(
  // Disable warnings arising from generated routing code
  scalacOptions += "-Wconf:src=routes/.*:silent",
  // Disable fatal warnings and warnings from discarding values
  scalacOptions ~= {
    opts =>
      opts.filterNot(Set("-Xfatal-warnings", "-Ywarn-value-discard"))
  }
)
