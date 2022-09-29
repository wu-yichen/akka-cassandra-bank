package github.yichenwu.bank.app
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.{ActorRef, ActorSystem, Behavior, Scheduler}
import akka.http.scaladsl.Http
import akka.util.Timeout
import github.yichenwu.bank.actors.Bank
import github.yichenwu.bank.actors.PersistentBankAccount.Command
import github.yichenwu.bank.http.BankRouter

import scala.concurrent.ExecutionContext
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps
import scala.util.{Failure, Success}

trait RootCommand
final case class RetrieveBankActor(replyTo: ActorRef[ActorRef[Command]])
    extends RootCommand

object BankApp {

  def startServer(
      bank: ActorRef[Command]
  )(implicit system: ActorSystem[_], ec: ExecutionContext): Unit = {
    Http()
      .newServerAt("localhost", 9090)
      .bind(new BankRouter(bank).routes)
      .onComplete {
        case Success(binding) =>
          val address = binding.localAddress
          system.log.info(
            s"Server started successfully on ${address.getHostString}:${address.getPort}"
          )
        case Failure(exception) =>
          system.log.error(s"Server failed to start due to $exception")
          system.terminate()
      }
  }

  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[RootCommand] = Behaviors.setup { context =>
      val bankActor = context.spawn(Bank(), "bank")
      Behaviors.receiveMessage { case RetrieveBankActor(replyTo) =>
        replyTo ! bankActor
        Behaviors.same
      }
    }
    implicit val system: ActorSystem[RootCommand] =
      ActorSystem[RootCommand](rootBehavior, "root")
    implicit val scheduler: Scheduler = system.scheduler
    implicit val timeout: Timeout = Timeout(2 seconds)
    implicit val ec: ExecutionContext = system.executionContext

    system.ask(RetrieveBankActor).foreach(startServer)
  }
}
