import play.core.PlayVersion
import play.sbt.PlayImport._
import sbt.Keys.libraryDependencies
import sbt._

object AppDependencies {

  private val bootstrapVersion = "7.22.0"
  private val hmrcMongoVersion = "1.3.0"

  val compile = Seq(
    "uk.gov.hmrc"             %% "bootstrap-backend-play-28"         % bootstrapVersion,
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-work-item-repo-play-28" % hmrcMongoVersion,
    "uk.gov.hmrc"             %% "domain"                            % s"8.1.0-play-28",
    "org.typelevel"           %% "cats-core"                         % "2.9.0"
  )

  val test = Seq(
    "uk.gov.hmrc"             %% "bootstrap-test-play-28"     % bootstrapVersion    % "test, it",
    "uk.gov.hmrc.mongo"       %% "hmrc-mongo-test-play-28"    % hmrcMongoVersion    % "test, it",
    "org.mockito"             %% "mockito-scala"              % "1.17.12"           % "test, it"
  )
}
