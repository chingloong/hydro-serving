name := "hydro-serving"

updateOptions := updateOptions.value.withCachedResolution(true)

scalaVersion := Common.scalaCommonVersion

lazy val currentAppVersion = util.Properties.propOrElse("appVersion", "latest")

lazy val currentSettings: Seq[Def.Setting[_]] = Seq(
  version := currentAppVersion,

  parallelExecution in Test := false,
  parallelExecution in IntegrationTest := false,

  fork in(Test, test) := true,
  fork in(IntegrationTest, test) := true,
  fork in(IntegrationTest, testOnly) := true
)

lazy val root = project.in(file("."))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(currentSettings)
  .settings(Common.settings)
  .aggregate(
    manager,
    dummyRuntime
  )

lazy val codegen = project.in(file("codegen"))
  .settings(currentSettings)
  .settings(Common.settings)
  .settings(libraryDependencies ++= Dependencies.codegenDependencies)

lazy val manager = project.in(file("manager"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(currentSettings)
  .settings(Common.settings)
  .settings(libraryDependencies ++= Dependencies.hydroServingManagerDependencies)
  .dependsOn(codegen)


lazy val dummyRuntime = project.in(file("dummy-runtime"))
  .configs(IntegrationTest)
  .settings(Defaults.itSettings: _*)
  .settings(currentSettings)
  .settings(Common.settings)
  .settings(libraryDependencies ++= Dependencies.hydroServingDummyRuntimeDependencies)
