package github.yichenwu.bank.actors
import akka.NotUsed
import akka.actor.typed.scaladsl.{ActorContext, Behaviors}
import akka.actor.typed.{ActorRef, ActorSystem, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import github.yichenwu.bank.actors.PersistentBankAccount.{Command, Response}
import github.yichenwu.bank.actors.PersistentBankAccount.Command._
import github.yichenwu.bank.actors.PersistentBankAccount.Response._

import java.util.UUID

object Bank {
  sealed trait Event
  final case class BankAccountCreated(id: String) extends Event

  final case class State(accounts: Map[String, ActorRef[Command]])

  def commandHandler(
      context: ActorContext[Command]
  ): (State, Command) => Effect[Event, State] = (state, command) =>
    command match {
      case create @ CreateBankAccount(_, _, _, _) =>
        val id = UUID.randomUUID().toString
        val child = context.spawn(PersistentBankAccount(id), id)
        Effect.persist(BankAccountCreated(id)).thenReply(child)(_ => create)
      case update @ UpdateBankAccount(id, _, _, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) => Effect.reply(account)(update)
          case None          => Effect.reply(replyTo)(BankAccountUpdatedResponse(None))
        }
      case get @ GetBankAccount(id, replyTo) =>
        state.accounts.get(id) match {
          case Some(account) => Effect.reply(account)(get)
          case None          => Effect.reply(replyTo)(GetBankAccountResponse(None))
        }
    }

  def eventHandler(context: ActorContext[Command]): (State, Event) => State =
    (state, event) =>
      event match {
        case BankAccountCreated(id) =>
          val account = context
            .child(id)
            .getOrElse(context.spawn(PersistentBankAccount(id), id))
            .asInstanceOf[ActorRef[Command]]
          state.copy(accounts = state.accounts + (id -> account))
      }

  def apply(): Behavior[Command] = {
    Behaviors.setup { context: ActorContext[Command] =>
      EventSourcedBehavior[Command, Event, State](
        persistenceId = PersistenceId.ofUniqueId("bank"),
        emptyState = State(Map.empty),
        commandHandler = commandHandler(context),
        eventHandler = eventHandler(context)
      )
    }
  }
}

object BankPlayground {
  def main(args: Array[String]): Unit = {
    val rootBehavior: Behavior[NotUsed] = Behaviors.setup { context =>
      val bank = context.spawn(Bank(), "bank")
      val log = context.log
      val responseHandler = context.spawn(
        Behaviors.receiveMessage[Response] {
          case BankAccountCreatedResponse(id) =>
            log.info(s"bank account created successfully: $id")
            Behaviors.same
          case GetBankAccountResponse(maybeBankAccount) =>
            log.info(s"Account details $maybeBankAccount")
            Behaviors.same
        },
        "replyHandler"
      )
//      bank ! CreateBankAccount("ywu", "euro", 10000000.1, responseHandler)
      bank ! GetBankAccount(
        "07eeb4bd-cf8d-48c4-b56a-bc54baa2e0d6",
        responseHandler
      )
      Behaviors.empty
    }
    ActorSystem[NotUsed](rootBehavior, "root")
  }
}
