import java.io.File;

lazy val processingCp = Def.setting(
  Attributed.blankSeq(
    IO
      .read(file("cache/classpath.txt"))
      .split(":")
      .map(name => file(".") / name.trim)
  )
)
lazy val buildTool =
  taskKey[File]("Build as processing tool for development")
lazy val buildToolProd =
  taskKey[File]("Build as processing tool for production")

lazy val sharedSettings = Seq(
  scalaVersion := "3.3.0",
  Compile / unmanagedJars ++= processingCp.value
);

lazy val codegenProject = project
  .in(file("codegen"))
  .settings(sharedSettings)
  .settings(
    name := "seekprog-codegen",
    scalacOptions ++= Seq(
      "-no-indent"
    )
  );

lazy val root = project
  .in(file("."))
  .dependsOn(codegenProject)
  .settings(sharedSettings)
  .settings(
    name := "seekprog",
    version := "0.1.0-SNAPSHOT",
    run / fork := true,
    connectInput := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "org.scalafx" %% "scalafx" % "20.0.0-R31"
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % "0.14.5"),
    Compile / sourceGenerators += Def.task {
      val rootDir = sourceManaged.value / "seekprog"
      IO.delete(rootDir)
      val cp = (Compile / dependencyClasspath).value
      val r = (Compile / runner).value
      val s = streams.value
      r.run(
        "net.kgtkr.seekprog.codegen.Codegen",
        cp.files,
        Array(rootDir.getAbsolutePath()),
        s.log
      ).failed foreach (sys error _.getMessage)
      (rootDir ** "*.scala").get
    },
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
    },
    buildToolProd := {
      val buildToolResult = buildTool.value;
      val srcDir = (Compile / sourceDirectory).value;
      val docDir = (Compile / doc).value;
      val zipDist = baseDirectory.value / "Seekprog.zip";

      IO.withTemporaryDirectory(tmpDir => {
        val distDir = tmpDir / "Seekprog";
        IO.copyDirectory(
          buildToolResult,
          distDir
        )

        IO.copyDirectory(
          srcDir,
          distDir / "src"
        )

        (distDir / "examples").mkdir()
        IO.copyDirectory(
          docDir,
          distDir / "reference"
        )

        IO.zip(Path.allSubpaths(distDir), zipDist, None)
      });

      zipDist
    }
  )
