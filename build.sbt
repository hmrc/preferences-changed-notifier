import uk.gov.hmrc.DefaultBuildSettings.*

val appName: String = "preferences-changed-notifier"

ThisBuild / majorVersion := 0
ThisBuild / scalaVersion := "2.13.8"

lazy val microservice = Project("preferences-changed-notifier", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions += "-Wconf:src=routes/.*:s",
    Compile / scalafmtOnCompile := true,
    Test / scalafmtOnCompile := true
  )
  .configs(IntegrationTest)
  .settings(integrationTestSettings(): _*)
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(CodeCoverageSettings.settings: _*)

addCommandAlias("buildall", ";clean;compile;scalafmt;coverage;test;it:test;scalastyle;coverageReport")