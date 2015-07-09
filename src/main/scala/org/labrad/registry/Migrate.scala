package org.labrad.registry

import java.io.File
import java.net.URI
import org.clapper.argot._
import org.clapper.argot.ArgotConverters._
import org.labrad.Client
import org.labrad.RegistryServerProxy
import org.labrad.data.Data
import scala.concurrent.{Await, Future}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.io.Source

object Migrate {

  def main(args: Array[String]): Unit = {
    val parser = new ArgotParser("labrad-migrate-registry",
      preUsage = Some("Migrate registry between managers and formats."),
      sortUsage = false
    )
    val srcOpt = parser.parameter[String](
      valueName = "src",
      description = "Location of source registry. " +
        "Can be labrad://[<pw>@]<host>[:<port>] to connect to a running manager, " +
        "or file://<path> to load data from a local file. " +
        "If the path points to a directory, we assume it is in the " +
        "legacy delphi format; if it points to a file, we assume it " +
        "is in the new SQLite format.",
      optional = false
    )
    val dstOpt = parser.parameter[String](
      valueName = "dst",
      description = "Location of destination registry. " +
        "Can be labrad://[<pw>@]<host>[:<port>] to send data to a running manager, " +
        "or file://<path> to write data to a local file. " +
        "If writing to a file, you must specify a file, not a directory, " +
        "and the data will be stored in the new SQLite format. " +
        "If sending the data to a running manager, the data will be stored " +
        "in whatever format that manager uses. " +
        "If not specified, we traverse the source registry but do not " +
        "transfer the data to a new location. This can be used as a dry run " +
        "to verify the integrity of the source registry before migration.",
      optional = true
    )
    val verbose = parser.flag[Boolean](List("v", "verbose"),
      "Print progress information during the migration")

    try {
      parser.parse(args)
    } catch {
      case e: ArgotUsageException =>
        println(e.message)
        return
      case e: Exception =>
        println(s"enexpected error: $e")
        return
    }

    val srcReg: RegistrySrc = {
      val uri = new URI(srcOpt.value.get)

      uri.getScheme match {
        case "labrad" =>
          new RemoteRegistry(connectToUri(uri))

        case "file" =>
          val file = new File(uri)
          require(file.exists, "source file does not exist")
          if (file.isDirectory) {
            new DelphiRegistry(file)
          } else {
            new SQLiteRegistry(file)
          }
      }
    }

    val dstRegOpt: Option[RegistrySink] = dstOpt.value.map { srcUriStr =>
      val uri = new URI(srcUriStr)

      uri.getScheme match {
        case "labrad" =>
          new RemoteRegistry(connectToUri(uri))

        case "file" =>
          val file = new File(uri)
          if (file.isDirectory) {
            sys.error("cannot use directory as destination uri")
          } else {
            new SQLiteRegistry(file)
          }
      }
    }

    val noisy = verbose.value.getOrElse(false)

    def traverse(srcPath: Seq[String], dstPath: Seq[String]): Unit = {

      val t0 = System.nanoTime()
      val (dirs, values) = srcReg.get(srcPath)
      val t1 = System.nanoTime()

      for (dstReg <- dstRegOpt) {
        dstReg.set(srcPath, values)
      }
      val t2 = System.nanoTime()

      if (noisy) {
        println(s" ${srcPath.mkString("/")} (time [ms]: load=${((t1-t0) / 1e6).toInt}, save=${((t2-t1) / 1e6).toInt})")
      }

      for (dir <- dirs.sorted) {
        traverse(srcPath :+ dir, dstPath :+ dir)
      }
    }

    val tStart = System.nanoTime()
    traverse(Seq(""), Seq(""))
    val tEnd = System.nanoTime()
    println(s"total time [s]: ${((tEnd - tStart) / 1e9).toInt}")

    val failed = failures.result
    println(s"${failed.length} failures")
    for (s <- failed) {
      println()
      println(s)
    }
  }

  def connectToUri(uri: URI): Client = {
    val host = uri.getHost

    val port = uri.getPort match {
      case -1 => sys.env.get("LABRADPORT").map(_.toInt).getOrElse(7682)
      case port => port
    }

    def getPassword(): Array[Char] = {
      sys.env.get("LABRADPASSWORD").map(_.toCharArray).getOrElse {
        println(s"Password for registry at $uri:")
        System.console.readPassword()
      }
    }
    val password = uri.getUserInfo match {
      case null => getPassword()
      case info => info.split(":") match {
        case Array() => getPassword()
        case Array(pw) => pw.toCharArray
        case Array(u, pw) => pw.toCharArray
      }
    }

    val cxn = new Client(host = host, port = port, password = password)
    cxn.connect()
    cxn
  }

  val failures = Seq.newBuilder[String]

  /**
   * Interface to the source registry
   */
  trait RegistrySrc {
    def get(path: Seq[String]): (Seq[String], Map[String, Data])
  }

  /**
   * Interface to the destination registry
   */
  trait RegistrySink {
    def set(path: Seq[String], keys: Map[String, Data]): Unit
  }

  /**
   * Remote registry that we connect to over the network (source or sink).
   */
  class RemoteRegistry(cxn: Client) extends RegistrySrc with RegistrySink {
    val reg = new RegistryServerProxy(cxn)

    def get(path: Seq[String]): (Seq[String], Map[String, Data]) = {
      await(reg.cd(path))
      val (dirs, keys) = await(reg.dir())

      val futures = Map.newBuilder[String, Future[Data]]

      val pkt = reg.packet()
      pkt.cd(path)
      for (key <- keys.sorted) {
        futures += key -> pkt.get(key)
      }
      await(pkt.send())

      val values = futures.result.map {
        case (key, f) => key -> await(f)
      }

      (dirs, values)
    }

    def set(path: Seq[String], keys: Map[String, Data]): Unit = {
      val pkt = reg.packet()
      pkt.cd(path, create = true)
      for ((key, value) <- keys) {
        pkt.set(key, value)
      }
      await(pkt.send())
    }
  }

  /**
   * Legacy delphi registry format on disk (source only).
   */
  class DelphiRegistry(root: File) extends RegistrySrc {
    val decoded = """%/\:*?"<>|."""
    val encoded = """pfbcaqQlgPd"""
    val dirSuffix = ".dir"
    val keySuffix = ".key"

    def encodeFilename(s: String): String = {
      val result = new StringBuilder
      for (c <- s) {
        if (decoded.contains(c)) {
          result ++= ("%" + encoded(decoded.indexOf(c)))
        } else {
          result += c
        }
      }
      result.toString
    }

    def decodeFilename(s: String): String = {
      val result = new StringBuilder
      var escape = false
      for (c <- s) {
        if (c == '%') {
          escape = true
        } else if (escape) {
          result += decoded(encoded.indexOf(c))
          escape = false
        } else {
          result += c
        }
      }
      result.toString
    }

    def dirFile(path: Seq[String]): File = {
      var file = root
      for (d <- path; if d.nonEmpty) {
        file = new File(file, encodeFilename(d) + dirSuffix)
      }
      file
    }

    def get(path: Seq[String]): (Seq[String], Map[String, Data]) = {
      val loc = dirFile(path)

      val dirs = Seq.newBuilder[String]
      val values = Map.newBuilder[String, Data]
      for {
        f <- loc.listFiles
        name = f.getName
      } {
        if (f.isDirectory && name.endsWith(dirSuffix)) {
          dirs += decodeFilename(name.stripSuffix(dirSuffix))
        } else if (f.isFile && name.endsWith(keySuffix)) {
          val key = decodeFilename(name.stripSuffix(keySuffix))
          val src = Source.fromFile(f)
          val s = src.mkString
          src.close()
          try {
            val data = DelphiParsers.parseData(s)
            values += key -> data
          } catch {
            case e: Exception =>
              println(s"failed to parse $f: $s")
              failures += s
              //throw e
          }
        }
      }
      (dirs.result, values.result)
    }
  }

  /**
   * SQLite registry format on disk (source or sink).
   */
  class SQLiteRegistry(file: File) extends RegistrySrc with RegistrySink {
    val store = SQLiteStore(file)

    def find(path: Seq[String], create: Boolean = false): store.Dir = {
      var loc = store.root
      for (dir <- path; if dir.nonEmpty) {
        loc = store.child(loc, dir, create = create)._1
      }
      loc
    }

    def get(path: Seq[String]): (Seq[String], Map[String, Data]) = {
      val loc = find(path)
      val (dirs, keys) = store.dir(loc)

      val values = Map.newBuilder[String, Data]
      for (key <- keys) {
        val data = store.getValue(loc, key, default = None)
        values += key -> data
      }
      (dirs, values.result)
    }

    def set(path: Seq[String], keys: Map[String, Data]): Unit = {
      val loc = find(path, create = true)
      for ((key, value) <- keys) {
        store.setValue(loc, key, value)
      }
    }
  }

  /**
   * Wait for a future to complete; shorthand for Await.result
   */
  def await[T](f: Future[T]): T = {
    Await.result(f, 60.seconds)
  }
}
