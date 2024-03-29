import org.apache.commons.compress.archivers.ArchiveEntry;
import org.apache.commons.compress.archivers.ArchiveInputStream;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream;
import org.apache.commons.compress.utils.IOUtils;
import sbt.Keys._
import java.nio.file.Files
import java.io.File;
import sbt.*

object Processing {
  val versionNumber = "1292"
  val version = "4.2"
  val tag = s"processing-${versionNumber}-${version}"
  val assetNameWithoutExt = s"processing-${version}-linux-x64"
  val assetName = s"${assetNameWithoutExt}.tgz"
  val binUrl =
    s"https://github.com/processing/processing4/releases/download/${tag}/${assetName}"

  lazy val downloadProcessingTask = Def.task[File] {
    val processingBasePath = (LocalRootProject / target).value / "processing";
    processingBasePath.mkdirs();
    val processingOutPath = processingBasePath / assetNameWithoutExt;

    if (!processingOutPath.exists()) {
      IO.withTemporaryDirectory(tmpDir => {
        val tgzFile = tmpDir / assetName;
        val dir = tmpDir / assetNameWithoutExt;

        Files.copy(
          new URL(binUrl).openStream(),
          tgzFile.toPath()
        );

        val in = new TarArchiveInputStream(
          new GzipCompressorInputStream(new URL(binUrl).openStream())
        );
        for (
          entry <- Iterator
            .continually({ in.getNextEntry() })
            .takeWhile(_ != null)
        ) {
          val file = dir.toPath().resolve(entry.getName()).toFile();
          if (entry.isDirectory()) {
            file.mkdirs();
          } else {
            val parent = file.getParentFile();
            parent.mkdirs();
            IOUtils.copy(in, Files.newOutputStream(file.toPath()));
          }
        }
        IO.copyDirectory(dir, processingOutPath);
      });
    }

    processingOutPath / s"processing-${version}"
  }

  lazy val javaModeCpTask = Def.task[Seq[File]] {
    (downloadProcessingTask.value / "modes" / "java" / "mode" ** "*.jar").get
  }

  lazy val coreCpTask = Def.task[Seq[File]] {
    (downloadProcessingTask.value / "core" / "library" ** "*.jar").get
  }

  lazy val libCpTask = Def.task[Seq[File]] {
    (downloadProcessingTask.value / "lib" ** "*.jar").get
  }

  lazy val allCpTask = Def.task[Seq[File]] {
    libCpTask.value ++
      coreCpTask.value ++
      javaModeCpTask.value
  }

}
