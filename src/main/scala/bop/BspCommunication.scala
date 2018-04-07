package bop

import java.io.File
import java.nio.file.Files

import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.{BuildClientCapabilities, InitializeBuildParams, InitializedBuildParams}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.Scheduler
import monix.reactive.observables.ConnectableObservable
import org.langmeta.jsonrpc.{BaseProtocolMessage, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import org.scalasbt.ipcsocket.UnixDomainSocket
import org.slf4j.LoggerFactory

import scala.concurrent.Promise
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Random, Success}

class BspSession(val messages: ConnectableObservable[BaseProtocolMessage],
                 private implicit val client: LanguageClient,
                 server: LanguageServer,
                 initializedBuildParams: InitializeBuildParams,
                 cleanup: Task[Unit]
                ) {

  // TODO should use IDEA logging
  private val logger = Logger(LoggerFactory.getLogger(classOf[BspSession]))

  /** Task starts client-server connection and connects message stream. Attach consumers to messages before running this. */
  def run[T](task: LanguageClient => Task[T])(implicit scheduler: Scheduler): Task[T] = {
    val connection = messages.connect()
    val runningClientServer = startClientServer

    val whenDone = Task {
      logger.info("closing bsp connection")
      connection.cancel()
      runningClientServer.cancel()
    }

    val resultTask = for {
      initResult <- initRequest
      _ = endpoints.Build.initialized.notify(InitializedBuildParams())
      result <- task(client)
    } yield {
      result
    }

    resultTask
      .doOnCancel(whenDone)
      .doOnFinish {
        case Some(err) =>
          logger.error("bsp connection error", err)
          whenDone
        case None => whenDone
      }
  }

  private val initRequest =
    endpoints.Build.initialize.request(initializedBuildParams)

  private def startClientServer(implicit scheduler: Scheduler) =
    server.startTask
      .doOnFinish { errOpt => for {
        cleaned <- cleanup
      } yield {
          logger.info("client/server closed")
          errOpt.foreach { err =>
            logger.info(s"client/server closed with error: $err")
          }
        }
      }
      .doOnCancel(cleanup)
      .runAsync

}

object BspCommunication {


  def prepareSession(base: File)(implicit scheduler: Scheduler): Task[BspSession] = {

    // TODO should use IDEA logging
    val logger = Logger(LoggerFactory.getLogger(classOf[BspSession]))

    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)

    // TODO support windows pipes and tcp as well as sockets
    val sockdir = Files.createTempDirectory("bsp-")
    val sockfile = sockdir.resolve(s"$id.socket")
    sockfile.toFile.deleteOnExit()

    // TODO abstract build tool specific logic
    val bloopConfigDir = new File(base, ".bloop-config").getCanonicalFile
    assert(bloopConfigDir.exists())
    val bspCommand = s"bloop bsp --protocol local --socket $sockfile --verbose"

    val bspReady = Promise[Unit]()
    val proclog = ProcessLogger.apply { msg =>
      logger.info(s"§ bloop: $msg")
      if (!bspReady.isCompleted && msg.contains(id)) bspReady.complete(Success(()))
    }
    // TODO kill bloop process on cancel / error?
    def runBloop = Process(bspCommand, base).run(proclog)
    val bspReadyTask = Task.fromFuture(bspReady.future)

    val initParams = InitializeBuildParams(
      rootUri = bloopConfigDir.toString,
      Some(BuildClientCapabilities(List("scala")))
    )

    def initSession = {
      val socket = new UnixDomainSocket(sockfile.toString)
      val messages = BaseProtocolMessage.fromInputStream(socket.getInputStream).replay
      val services = Services.empty
      val client: LanguageClient = new LanguageClient(socket.getOutputStream, logger)
      val server = new LanguageServer(messages, client, services, scheduler, logger)
      new BspSession(messages, client, server, initParams, cleanup(socket, sockfile.toFile))
    }

    def cleanup(socket: UnixDomainSocket, socketFile: File): Task[Unit] = Task.eval {
      socket.close()
      socket.shutdownInput()
      socket.shutdownOutput()
      if (socketFile.isFile) socketFile.delete()
    }

    for {
      bloopProcess <- Task(runBloop)
      _ <- bspReadyTask
    } yield {
      initSession
    }
  }

}
