import uk.gov.hmrc.DefaultBuildSettings.*

val appName: String = "preferences-changed-notifier"

ThisBuild / majorVersion := 1
ThisBuild / scalaVersion := "3.3.3"

lazy val microservice = Project("preferences-changed-notifier", file("."))
  .enablePlugins(play.sbt.PlayScala, SbtDistributablesPlugin)
  .disablePlugins(JUnitXmlReportPlugin) //Required to prevent https://github.com/scalatest/scalatest/issues/1427
  .settings(
    libraryDependencies ++= AppDependencies.compile ++ AppDependencies.test,
    // https://www.scala-lang.org/2021/01/12/configuring-and-suppressing-warnings.html
    // suppress warnings in generated routes files
    scalacOptions += "-Wconf:src=routes/.*:s",
    scalacOptions += "-feature"
  )
  .settings(resolvers += Resolver.jcenterRepo)
  .settings(CodeCoverageSettings.settings: _*)

lazy val it = Project(id = "it", base = file("it"))
  .enablePlugins(PlayScala, ScalafmtPlugin)
  .dependsOn(microservice % "test->test") // the "test->test" allows reusing test code and test dependencies
  .settings(libraryDependencies ++= AppDependencies.it)

Test / test := (Test / test)
  .dependsOn(scalafmtCheckAll)
  .value

it / test := (it / Test / test)
  .dependsOn(scalafmtCheckAll, it/scalafmtCheckAll)
  .value
