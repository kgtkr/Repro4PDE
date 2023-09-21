import java.nio.charset.StandardCharsets
import java.util.Properties

lazy val deployToolDev =
  taskKey[Unit]("Build as processing tool and deploy for development")
lazy val buildTool =
  taskKey[File]("Build as processing tool")
lazy val codegenSeekprog =
  taskKey[Seq[File]]("Seekprog codegen")
lazy val filteredfullClasspathAsJars =
  taskKey[Classpath]("filteredfullClasspathAsJars")

val circeDependencies = Seq(
  "io.circe" %% "circe-core",
  "io.circe" %% "circe-generic",
  "io.circe" %% "circe-parser"
).map(_ % "0.14.5");

lazy val sharedSettings = Seq(
  scalaVersion := "3.3.1",
  version := "0.1.0-SNAPSHOT",
  filteredfullClasspathAsJars := {
    val files = (Runtime / fullClasspathAsJars).value
    val exclude =
      Processing.allCpTask.value.map(_.getPath()).toSet
    files
      .filterNot(jar => exclude.contains(jar.data.getPath()))
      .filterNot(jar => jar.data.getName().contains("javafx-web"))
  },
  scalacOptions ++= Seq(
    "-no-indent",
    "-Wunused:all"
  ),
  run / fork := true,
  connectInput := true
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
        "seekprog.codegen.Codegen",
        cp.files,
        Array(rootDir.getAbsolutePath()),
        s.log
      ).failed foreach (sys error _.getMessage)
      (rootDir ** "*.scala").get
    },
    Compile / unmanagedJars ++= Processing.coreCpTask.value
  );

lazy val sharedProject = project
  .in(file("shared"))
  .settings(sharedSettings)
  .settings(
    name := "seekprog-shared",
    libraryDependencies ++= circeDependencies
  );

lazy val utilsProject = project
  .in(file("utils"))
  .settings(sharedSettings)
  .settings(
    name := "seekprog-utils"
  );

lazy val runtimeProject = project
  .in(file("runtime"))
  .dependsOn(sharedProject)
  .settings(sharedSettings)
  .settings(
    name := "seekprog-runtime",
    libraryDependencies ++= circeDependencies,
    Compile / sourceGenerators += codegenProject / codegenSeekprog,
    Compile / unmanagedJars ++= Processing.coreCpTask.value
  );

lazy val toolProject = project
  .in(file("tool"))
  .settings(sharedSettings)
  .settings(
    name := "seekprog-tool",
    assembly / assemblyExcludedJars := Attributed.blankSeq(
      Processing.allCpTask.value
    ),
    Compile / unmanagedJars ++= Processing.libCpTask.value
  );

lazy val appProject = project
  .in(file("app"))
  .dependsOn(utilsProject, sharedProject)
  .settings(sharedSettings)
  .settings(
    name := "seekprog-app",
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

lazy val buildToolBase = Def.task {
  val distDir = IO.createTemporaryDirectory / "Seekprog"

  IO.copyFile(
    baseDirectory.value / "tool.properties",
    distDir / "tool.properties"
  )

  val toolDir = distDir / "tool"
  toolDir.mkdir()

  val jarDir =
    IO.copyFile(
      (toolProject / assembly).value,
      toolDir / "Seekprog.jar"
    )

  val libDir = toolDir / "lib"
  libDir.mkdir()

  val appCp = (appProject / filteredfullClasspathAsJars).value
    .map(_.data);
  for (file <- appCp) {
    IO.copyFile(
      file,
      libDir / file.getName()
    )
  }

  IO.write(
    libDir / "app-classpath.txt",
    appCp.map(_.getName()).mkString(","),
    StandardCharsets.UTF_8
  )

  val runtimeCp = (runtimeProject / filteredfullClasspathAsJars).value
    .map(_.data);
  for (file <- runtimeCp) {
    IO.copyFile(
      file,
      libDir / file.getName()
    )
  }

  IO.write(
    libDir / "runtime-classpath.txt",
    runtimeCp.map(_.getName()).mkString(","),
    StandardCharsets.UTF_8
  )

  distDir
}

lazy val root = project
  .in(file("."))
  .aggregate(
    codegenProject,
    sharedProject,
    utilsProject,
    runtimeProject,
    toolProject,
    appProject
  )
  .settings(
    name := "seekprog",
    buildTool := {
      val dir = buildToolBase.value;
      val srcDir = (Compile / sourceDirectory).value;
      val docDir = (Compile / doc).value;
      val dist = crossTarget.value / "Seekprog.zip";

      IO.copyDirectory(
        srcDir,
        dir / "src"
      )

      (dir / "examples").mkdir()
      IO.copyDirectory(
        docDir,
        dir / "reference"
      )

      IO.zip(Path.allSubpaths(dir.getParentFile()), dist, None)

      dist
    },
    deployToolDev := {
      val dir = buildToolBase.value;

      val properties = new Properties();
      IO.load(properties, baseDirectory.value / "dev.properties")
      val toolsDir = new File(properties.getProperty("PROCESSING_TOOLS_DIR"));

      val toolDir = toolsDir / "SeekprogDev";
      if (toolDir.exists()) {
        IO.delete(toolDir)
      }

      IO.copyDirectory(
        dir,
        toolDir
      );
      IO.delete(dir)

      IO.write(
        toolDir / "tool.properties",
        "name=SeekprogDev",
        IO.utf8,
        append = true
      )

      IO.move(
        toolDir / "tool" / "Seekprog.jar",
        toolDir / "tool" / "SeekprogDev.jar"
      )
    }
  )
