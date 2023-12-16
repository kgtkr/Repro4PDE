lazy val deployToolDev =
  taskKey[Unit]("Build as processing tool and deploy for development")
lazy val buildTool =
  taskKey[File]("Build as processing tool")

lazy val processingTool = new ProcessingTool(
  allProjects,
  toolProject,
  appProject,
  runtimeProject
);

lazy val circeDependencies = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % "0.14.5");

lazy val sharedSettings = Seq(
  scalaVersion := "3.3.1",
  version := "0.1.0-SNAPSHOT",
  scalacOptions ++= Seq(
    "-no-indent",
    "-Wunused:all"
  ),
  run / fork := true,
  connectInput := true,
  libraryDependencies += "com.github.rssh" %% "dotty-cps-async" % "0.9.19"
);

lazy val codegenProject = project
  .in(file("codegen"))
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-codegen",
    Compile / unmanagedJars ++= Processing.coreCpTask.value
  );

lazy val runtimeSharedProject = project
  .in(file("runtime-shared"))
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-runtime-shared",
    libraryDependencies ++= circeDependencies
  );

lazy val utilsProject = project
  .in(file("utils"))
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-utils"
  );

lazy val runtimeProject = project
  .in(file("runtime"))
  .dependsOn(runtimeSharedProject)
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-runtime",
    libraryDependencies ++= circeDependencies,
    Compile / sourceGenerators += Def.task {
      val rootDir = sourceManaged.value / "repro4pde"
      IO.delete(rootDir)
      val cp = (codegenProject / Runtime / fullClasspath).value
      val r = (Compile / runner).value
      val s = streams.value
      r.run(
        "repro4pde.codegen.Codegen",
        cp.files,
        Array(rootDir.getAbsolutePath()),
        s.log
      ).failed foreach (sys error _.getMessage)
      (rootDir ** "*.scala").get
    },
    Compile / unmanagedJars ++= Processing.coreCpTask.value
  );

lazy val toolProject = project
  .in(file("tool"))
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-tool",
    assembly / assemblyExcludedJars := Attributed.blankSeq(
      Processing.allCpTask.value
    ),
    Compile / unmanagedJars ++= Processing.libCpTask.value
  );

lazy val appProject = project
  .in(file("app"))
  .dependsOn(utilsProject, runtimeSharedProject)
  .settings(sharedSettings)
  .settings(
    name := "repro4pde-app",
    libraryDependencies ++= Seq(
      "org.scalafx" %% "scalafx" % "20.0.0-R31" excludeAll (ExclusionRule(
        organization = "org.openjfx"
      )),
      "io.github.java-diff-utils" % "java-diff-utils" % "4.12"
    ),
    libraryDependencies ++= circeDependencies,
    libraryDependencies ++= Seq(
      "javafx-base",
      "javafx-controls",
      "javafx-fxml",
      "javafx-graphics",
      "javafx-media",
      "javafx-swing",
      "javafx-web"
    ).map(artifact =>
      Seq(
        "linux",
        "linux-aarch64",
        "mac-aarch64",
        "mac",
        "win"
      ).foldLeft("org.openjfx" % artifact % "20")(_ classifier _),
    ),
    Compile / unmanagedJars ++= Processing.javaModeCpTask.value,
    Compile / unmanagedJars ++= Processing.libCpTask.value
  );

lazy val allProjects = Seq(
  codegenProject,
  runtimeSharedProject,
  utilsProject,
  runtimeProject,
  toolProject,
  appProject
);

lazy val root = project
  .in(file("."))
  .aggregate(
    allProjects.map(p => p: ProjectReference): _*
  )
  .settings(sharedSettings)
  .settings(
    name := "repro4pde",
    buildTool := processingTool.buildToolTask.value,
    deployToolDev := processingTool.deployToolDevTask.value
  )
