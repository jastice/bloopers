package bop

import java.io.File
import java.nio.file.Files

import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.{BuildClientCapabilities, InitializeBuildParams, InitializedBuildParams, WorkspaceBuildTargetsRequest}
import com.typesafe.scalalogging.Logger
import monix.eval.Task
import monix.execution.{Cancelable, ExecutionModel, Scheduler}
import org.langmeta.jsonrpc.{BaseProtocolMessage, Services}
import org.langmeta.lsp.{LanguageClient, LanguageServer}
import org.scalasbt.ipcsocket.UnixDomainSocket
import org.slf4j.LoggerFactory

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Promise}
import scala.sys.process.{Process, ProcessLogger}
import scala.util.{Random, Success}

class IntelliBloop {}

object IntelliBloop {

  def main(args: Array[String]): Unit = {

    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    implicit val scheduler: Scheduler = Scheduler(
      pool, ExecutionModel.AlwaysAsyncExecution)

    val projectRoot = new File(".").getCanonicalFile

    val initClient = initialize(projectRoot).onErrorHandle(err => throw err)
    def targetsReq(implicit client: LanguageClient) =
      endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())

    val projectTask = for {
      runnerAndClient <- initClient
      client = runnerAndClient._2
      targets <- targetsReq(client)
    } yield {
      // TODO handle error response
      println(s"targets: $targets")
      for {
        target <- targets.right.get.targets
      } yield {
        println(s"target: $target")
        val uri = target.id.get.uri
        val name = target.displayName

        (uri,name)
      }
    }

    val result = Await.result(projectTask.runAsync, Duration.Inf)

    println("result: " + projectTask)
  }

  def initialize(base: File)(implicit scheduler: Scheduler): Task[(Cancelable, LanguageClient)] = {

    // TODO should use IDEA logging
    val logger = Logger(LoggerFactory.getLogger(classOf[IntelliBloop]))

    val id = java.lang.Long.toString(Random.nextLong(), Character.MAX_RADIX)

    // TODO support windows pipes and tcp as well as sockets
    val sockdir = Files.createTempDirectory("bsp-")
    val sockfile = sockdir.resolve(s"$id.socket")
    sockfile.toFile.deleteOnExit()

    // TODO abstract build tool specific logic
    println(s"base: $base")
    val bloopConfigDir = new File(base, ".bloop-config").getCanonicalFile
    println(s"bloopConfig: $bloopConfigDir")
    assert(bloopConfigDir.exists())
    val bspCommand = s"bloop bsp --protocol local --socket $sockfile --verbose"

    val bspReady = Promise[Unit]()
    val proclog = ProcessLogger.apply { msg =>
      logger.info(s"§ bloop: $msg")
      if (!bspReady.isCompleted && msg.contains(id)) bspReady.complete(Success(()))
    }
    // TODO kill bloop process on cancel / error
    val runBloop = Task.eval { Process(bspCommand, base).run(proclog) }
    val bspReadyTask = Task.fromFuture(bspReady.future)

    val initSocket = Task { new UnixDomainSocket(sockfile.toString) }

    def initServer(socket: UnixDomainSocket) = Task {
      val client: LanguageClient = new LanguageClient(socket.getOutputStream, logger)
      val messages = BaseProtocolMessage.fromInputStream(socket.getInputStream)
      val services = Services.empty
      val server = new LanguageServer(messages, client, services, scheduler, logger)
      (client, server)
    }

    def initializeServerReq(implicit client: LanguageClient) = {
      println("init request")
      endpoints.Build.initialize.request(
        InitializeBuildParams(
          rootUri = bloopConfigDir.toString,
          Some(BuildClientCapabilities(List("scala")))
        )
      )
    }

    def sendInitializedNotification(implicit client: LanguageClient): Unit = endpoints.Build.initialized.notify(InitializedBuildParams())


    def cleanup(socket: UnixDomainSocket): Task[Unit] = Task.eval {
      logger.warn("cleaning up socket!!")
      socket.close()
      socket.shutdownInput()
      socket.shutdownOutput()
      sockfile.toFile.delete()
    }

    def startClientServer(server: LanguageServer, socket: UnixDomainSocket) =
      server.startTask
        .doOnFinish { errOpt => Task {
          cleanup(socket)
          logger.info("client/server closed")
          errOpt.foreach { err =>
            logger.info(s"client/server closed with error: $err")
          }
        }}
        .doOnCancel(cleanup(socket))
        .runAsync

    val initializeSequence = for {
      bloopProcess <- runBloop
      _ <- bspReadyTask
      _ = println("bsp ready")
      socket <- initSocket
      clientServer <- initServer(socket)
      client = clientServer._1
      server = clientServer._2
      runningClientServer = startClientServer(server, socket)
      _ = println(s"server initialized")
      init <- initializeServerReq(client)
      _ = sendInitializedNotification(client)
    } yield {
      // TODO handle init error response
      println(s"init result: $init")
      (runningClientServer, client)
    }

//    val (client, server, runningClientServer) = Await.result(initClientServerTask.runAsync, Duration.Inf)

//    val initializeSequence = for {
//      clientServer <- initClientServerTask
//      client = clientServer._1
//      runningClientServer = clientServer._3
//      init <- initializeServerReq(client)
//    } yield {
//
//      println(s"init result: $init")
//      init.foreach(_ => sendInitializedNotification(client))
//      (runningClientServer, client)
//    }

    initializeSequence
  }

}
