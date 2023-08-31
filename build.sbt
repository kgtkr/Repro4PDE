val scala3Version = "3.3.0"
lazy val processingCp = Def.setting(
  Attributed.blankSeq(
    IO
      .read(file("cache/classpath.txt"))
      .split(":")
      .map(name => baseDirectory.value / name.trim)
  )
)
lazy val buildTool = taskKey[File]("Build as processing tool")

lazy val root = project
  .in(file("."))
  .settings(
    name := "seekprog",
    version := "0.1.0-SNAPSHOT",
    scalaVersion := scala3Version,
    run / fork := true,
    connectInput := true,
    Compile / unmanagedJars ++= processingCp.value,
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
    buildTool := {
      val distDir = baseDirectory.value / "tooldist"
      if (distDir.exists()) distDir.delete()
      distDir.mkdir()

      val toolProperties = distDir / "tool.properties"
      IO.copyFile(
        baseDirectory.value / "tool.properties",
        toolProperties
      )

      val toolDir = distDir / "tool"
      toolDir.mkdir()

      val jarDir =
        IO.copyFile(
          (Compile / packageBin).value,
          toolDir / "Seekprog.jar"
        )
      val exclude = processingCp.value.map(_.data.getPath()).toSet
      for (
        file <- (Compile / dependencyClasspathAsJars).value
          .filterNot(jar => exclude.contains(jar.data.getPath()))
      ) {
        IO.copyFile(
          file.data,
          toolDir / file.data.getName()
        )
      }

      distDir
    }
  )
