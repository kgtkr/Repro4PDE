val scala3Version = "3.3.0"
lazy val processingCp = Def.setting(
  Attributed.blankSeq(
    IO
      .read(file("cache/classpath.txt"))
      .split(":")
      .map(name => baseDirectory.value / name.trim)
  )
)
lazy val buildTool = taskKey[Unit]("Build as processing tool")

lazy val root = project
  .in(file("."))
  .settings(
    name := "seekprog",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    run / fork := true,
    connectInput := true,
    Compile / unmanagedJars ++= processingCp.value,
    bgCopyClasspath := false,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "org.scalafx" %% "scalafx" % "20.0.0-R31"
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % "0.14.5"),
    scalacOptions ++= Seq(
      "-no-indent"
    ),
    Compile / mainClass := Some("net.kgtkr.seekprog.Main"),
    assembly / assemblyExcludedJars := processingCp.value,
    assembly / assemblyMergeStrategy := {
      case PathList("META-INF", _*)      => MergeStrategy.discard
      case PathList("module-info.class") => MergeStrategy.discard
      case _                             => MergeStrategy.deduplicate
    },
    buildTool := {
      val distDir = baseDirectory.value / "tooldist"
      if (distDir.exists()) distDir.deleteOnExit()
      distDir.mkdir()

      val toolProperties = distDir / "tool.properties"
      toolProperties.createNewFile()
    }
  )
