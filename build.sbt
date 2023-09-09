lazy val deployToolDev =
  taskKey[Unit]("Build as processing tool and deploy for development")
lazy val buildTool =
  taskKey[File]("Build as processing tool")

lazy val sharedSettings = Seq(
  scalaVersion := "3.3.0",
  Compile / unmanagedJars ++= Processing.processingCpTask.value
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
    buildTool := BuildTool.buildTool.value,
    deployToolDev := BuildTool.deployToolDev.value
  )
