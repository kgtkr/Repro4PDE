import sbt.*
import sbt.Keys.*
import sbtassembly.AssemblyKeys.*
import java.nio.charset.StandardCharsets
import java.util.Properties
import java.io.FileInputStream
import java.io.InputStreamReader

class ProcessingTool(
    allProjects: Seq[Project],
    toolProject: Project,
    appProject: Project,
    runtimeProject: Project
) {
  lazy val buildToolBaseTask = Def.task {
    val excludeProcessingCp =
      Processing.allCpTask.value.map(_.getPath()).toSet

    def filterDependencies(
        cp: Seq[Attributed[File]]
    ): Seq[Attributed[File]] = {
      cp
        .filterNot(jar => excludeProcessingCp.contains(jar.data.getPath()))
        .filterNot(jar => jar.data.getName().contains("javafx-web"))
    }

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

    val appCp =
      filterDependencies((appProject / Runtime / fullClasspathAsJars).value)
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

    val runtimeCp =
      filterDependencies((runtimeProject / Runtime / fullClasspathAsJars).value)
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
  };

  lazy val buildToolTask = Def.taskDyn {
    val dir = buildToolBaseTask.value;
    val dist = crossTarget.value / "Seekprog.zip";
    val nameSrcDocs = flattenTasks(
      allProjects
        .map(p =>
          Def.task {
            val pName = (p / name).value
            val srcDir = (p / Compile / sourceDirectory).value
            val docDir = (p / Compile / doc).value;

            (pName, srcDir, docDir)
          }
        )
    ).value

    for ((pName, srcDir, docDir) <- nameSrcDocs) {
      IO.copyDirectory(
        srcDir,
        dir / "src" / pName
      )

      IO.copyDirectory(
        docDir,
        dir / "reference" / pName
      )
    }

    (dir / "examples").mkdir()

    IO.zip(Path.allSubpaths(dir.getParentFile()), dist, None)

    Def.task { dist }
  };

  lazy val deployToolDevTask = Def.task {
    val dir = buildToolBaseTask.value;

    val properties = new Properties();
    properties
      .load(
        new InputStreamReader(
          new FileInputStream(baseDirectory.value / "dev.properties"),
          StandardCharsets.UTF_8
        )
      )
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
  };

  // https://stackoverflow.com/questions/61055562/evaluating-a-list-of-tasks-inside-of-an-sbt-task
  def flattenTasks[A](
      tasks: Seq[Def.Initialize[Task[A]]]
  ): Def.Initialize[Task[List[A]]] =
    tasks.toList match {
      case Nil     => Def.task { Nil }
      case x :: xs => Def.taskDyn { flattenTasks(xs) map (x.value :: _) }
    }

}
