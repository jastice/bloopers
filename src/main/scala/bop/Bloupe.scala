package bop

import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit

import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema._
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.{ExecutionModel, Scheduler}
import org.langmeta.jsonrpc.{BaseProtocolMessage, Endpoint, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import org.scalasbt.ipcsocket.UnixDomainSocket
import org.slf4j.LoggerFactory

import scala.concurrent.duration._
import scala.concurrent.{Await, Promise}
import scala.sys.process._
import scala.util.{Random, Success}

object Bloupe {

  def main(args: Array[String]): Unit = {

    val logger: Logger = Logger(LoggerFactory.getLogger(getClass.getName))

    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    implicit val scheduler: Scheduler = Scheduler(
        pool, ExecutionModel.AlwaysAsyncExecution)

    val bloopConfigDir = new File("./.bloop-config").getCanonicalFile

    val sockdir = Files.createTempDirectory("bsp-")
    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)
    val sockfile = sockdir.resolve(s"$id.socket")
    sockfile.toFile.deleteOnExit()
    logger.info(s"unix socket file: $sockfile")

    val bspCommand = s"bloop bsp --protocol local --socket $sockfile --verbose"

    val bspReady = Promise[Unit]()
    val proclog = ProcessLogger.apply { msg =>
      logger.info(s"bloop: $msg")
      if (!bspReady.isCompleted && msg.contains(id)) bspReady.complete(Success())
    }
    Process(bspCommand).run(proclog)
    Await.ready(bspReady.future, 10.seconds)

    val sock = new UnixDomainSocket(sockfile.toString)
    implicit val client = new LanguageClient(sock.getOutputStream, logger)
    val messages = BaseProtocolMessage.fromInputStream(sock.getInputStream)
    val services = Services.empty
    val server = new LanguageServer(messages, client, services, scheduler, logger)
    val runningClientServer = server.startTask
      .doOnCancel(Task.eval(sock.close()))
      .runAsync(scheduler)

    try {

      val initializeServerReq = endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = bloopConfigDir.toString,
          Some(BuildClientCapabilities(List("scala")))
        )
      )

      val targetsReq = endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())

      import scalapb_circe.JsonFormat._
      object scalacOptions extends Endpoint[ScalacOptionsParams, ScalacOptions]("buildTarget/scalacOptions")

      object textDocuments extends Endpoint[BuildTargetTextDocumentsParams, BuildTargetTextDocuments]("buildTarget/textDocuments")

      val initialized = for {
        init <- initializeServerReq
        _ = endpoints.Build.initialized.notify(InitializedBuildParams())
        targets <- targetsReq
        targetIds = targets.right.get.targets.flatMap(_.id)
//        scalacOpts <- scalacOptions.request(ScalacOptionsParams(targetIds))
        textDocs <- textDocuments.request(BuildTargetTextDocumentsParams(targetIds))
        compile <- endpoints.BuildTarget.compile.request(CompileParams(targetIds))
      } yield {

        println("\n------\n")
        println(s"~~ init: $init\n------\n")
        println(s"~~ targets: ${targets.right.get.targets}\n------\n")
//        println(s"~~ scalacOptions: $scalacOpts\n------\n")
        println(s"~~ textDocs: $textDocs\n------\n")
        println(s"~~ compile: $compile\n------\n")
        println("\nXXX------XXX\n")
      }
      Await.ready(initialized.runAsync, 13.seconds)

    } finally {
      runningClientServer.cancel()
      sock.close()
      sock.shutdownInput()
      sock.shutdownOutput()
      pool.shutdown()
      if(pool.awaitTermination(4, TimeUnit.SECONDS))
        println("terminated orderly")
      else {
        println("unorderly!")
        val awaiting = pool.shutdownNow()
        println(awaiting)
      }
    }

    println("done blooping.")

  }
}
