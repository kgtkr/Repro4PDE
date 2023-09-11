lazy val deployToolDev =
  taskKey[Unit]("Build as processing tool and deploy for development")
lazy val buildTool =
  taskKey[File]("Build as processing tool")
lazy val codegenSeekprog =
  taskKey[Seq[File]]("Seekprog codegen")

lazy val sharedSettings = Seq(
  scalaVersion := "3.3.0",
  Compile / unmanagedClasspath ++= Processing.processingCpTask.value,
  scalacOptions ++= Seq(
    "-no-indent"
  )
);

lazy val codegenProject = project
  .in(file("codegen"))
  .settings(sharedSettings)
  .settings(
    name := "seekprog-codegen",
    codegenSeekprog := {
      val rootDir = sourceManaged.value / "seekprog"
      IO.delete(rootDir)
      val cp = (Compile / fullClasspath).value
      val r = (Compile / runner).value
      val s = streams.value
      r.run(
        "net.kgtkr.seekprog.codegen.Codegen",
        cp.files,
        Array(rootDir.getAbsolutePath()),
        s.log
      ).failed foreach (sys error _.getMessage)
      (rootDir ** "*.scala").get
    }
  );

lazy val root = project
  .in(file("."))
  .settings(sharedSettings)
  .settings(
    name := "seekprog",
    version := "0.1.0-SNAPSHOT",
    run / fork := true,
    connectInput := true,
    libraryDependencies ++= Seq(
      "org.scalameta" %% "munit" % "0.7.29" % Test,
      "org.scalafx" %% "scalafx" % "20.0.0-R31" excludeAll (ExclusionRule(
        organization = "org.openjfx"
      ))
    ),
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core",
      "io.circe" %% "circe-generic",
      "io.circe" %% "circe-parser"
    ).map(_ % "0.14.5"),
    libraryDependencies ++= Seq(
      "javafx-base",
      "javafx-controls",
      "javafx-fxml",
      "javafx-graphics",
      "javafx-media",
      "javafx-swing"
      // "javafx-web" // サイズが大きいかつ使わないので
    ).map(artifact =>
      Seq(
        "linux",
        "linux-aarch64",
        "mac-aarch64",
        "mac",
        "win"
      ).foldLeft("org.openjfx" % artifact % "20")(_ classifier _),
    ),
    Compile / sourceGenerators += codegenProject / codegenSeekprog,
    buildTool := BuildTool.buildTool.value,
    deployToolDev := BuildTool.deployToolDev.value
  )
