import sbt.Keys._
import sbt.*
import java.util.Properties

object BuildTool {
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
        (Compile / packageBin).value,
        toolDir / "Seekprog.jar"
      )
    val exclude =
      Processing.processingCpTask.value.map(_.getPath()).toSet
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

  lazy val buildTool = Def.task {
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
  }

  lazy val deployToolDev = Def.task {
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
}
