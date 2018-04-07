package bop

import java.io.File

import ch.epfl.scala.bsp.endpoints
import ch.epfl.scala.bsp.schema.{BuildTargetIdentifier, CompileParams, CompileReport, WorkspaceBuildTargetsRequest}
import monix.eval
import monix.execution.{ExecutionModel, Scheduler}
import monix.reactive.Consumer
import org.langmeta.jsonrpc.{BaseProtocolMessage, Response}
import org.langmeta.lsp.LanguageClient
import scala.concurrent.duration._

import scala.concurrent.Await

object IntelliBloop2 {

  private val msgConsumer = Consumer.foreach[BaseProtocolMessage] { msg =>
    val text = new String(msg.content)
    println(s"bsp :: $text")
  }

  def targetsRequest(implicit client: LanguageClient) =
    endpoints.Workspace.buildTargets.request(WorkspaceBuildTargetsRequest())

  private def compileRequest(targets: Seq[BuildTargetIdentifier])(implicit client: LanguageClient): eval.Task[Either[Response.Error, CompileReport]] =
    endpoints.BuildTarget.compile.request(CompileParams(targets))

  def main(args: Array[String]): Unit = {

    val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
    implicit val scheduler: Scheduler = Scheduler(pool, ExecutionModel.AlwaysAsyncExecution)

    val projectRoot = new File("/Users/jast/playspace/bloopers-bsp").getCanonicalFile

    def buildTasks(implicit client: LanguageClient) = for {
      targetsResponse <- targetsRequest
      targets = targetsResponse.right.get.targets.flatMap(_.id)
      compileResponse <- compileRequest(targets)
    } yield compileResponse

    val buildTask = for {
      session <- BspCommunication.prepareSession(projectRoot)
      _ = println("~~ session prepared")
      msgs = session.messages.consumeWith(msgConsumer) // TODO cancel this on finish?
      buildResponse <- session.run(buildTasks(_))
    } yield {
      buildResponse
    }

    try {
      val result = Await.result(buildTask.runAsync, 10.seconds)

      println(s"~~ result:\n$result")
      println("------")

    } finally {
      pool.shutdown()
    }


  }

}
