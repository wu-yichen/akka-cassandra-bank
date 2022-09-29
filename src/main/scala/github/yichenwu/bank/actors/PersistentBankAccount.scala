package github.yichenwu.bank.actors

import akka.actor.typed.{ActorRef, Behavior}
import akka.persistence.typed.PersistenceId
import akka.persistence.typed.scaladsl.{Effect, EventSourcedBehavior}
import github.yichenwu.bank.actors.PersistentBankAccount.Command._
import github.yichenwu.bank.actors.PersistentBankAccount.Response._

object PersistentBankAccount {

  // Command
  sealed trait Command
  object Command {
    final case class CreateBankAccount(
        user: String,
        currency: String,
        initialBalance: BigDecimal,
        replyTo: ActorRef[Response]
    ) extends Command
    final case class UpdateBankAccount(
        id: String,
        currency: String,
        amount: BigDecimal,
        replyTo: ActorRef[Response]
    ) extends Command
    final case class GetBankAccount(id: String, replyTo: ActorRef[Response])
        extends Command
  }

  // event
  sealed trait Event
  final case class BankAccountCreated(bankAccount: BankAccount) extends Event
  final case class BankAccountUpdated(amount: BigDecimal) extends Event

  //state
  final case class BankAccount(
      id: String,
      user: String,
      currency: String,
      balance: BigDecimal
  )

  //response
  sealed trait Response
  object Response {
    final case class BankAccountCreatedResponse(id: String) extends Response
    final case class BankAccountUpdatedResponse(
        maybeBankAccount: Option[BankAccount]
    ) extends Response
    final case class GetBankAccountResponse(
        maybeBankAccount: Option[BankAccount]
    ) extends Response

    final case class FailureResponse(reason: String) extends Response
  }

  // command-handler
  val commandHandler: (BankAccount, Command) => Effect[Event, BankAccount] =
    (state, command) =>
      command match {
        case CreateBankAccount(user, currency, initialBalance, replyTo) =>
          Effect
            .persist(
              BankAccountCreated(
                BankAccount(
                  state.id,
                  user,
                  currency = currency,
                  balance = initialBalance
                )
              )
            )
            .thenReply(replyTo)(_ => BankAccountCreatedResponse(state.id))
        case UpdateBankAccount(_, _, amount, replyTo) =>
          val newAmount = state.balance + amount
          if (newAmount < 0)
            Effect.reply(replyTo)(BankAccountUpdatedResponse(None))
          else {
            Effect
              .persist(BankAccountUpdated(amount))
              .thenReply(replyTo)(state =>
                BankAccountUpdatedResponse(Some(state))
              )
          }
        case GetBankAccount(_, replyTo) =>
          Effect.reply(replyTo)(GetBankAccountResponse(Some(state)))
      }

  // event-handler
  val eventHandler: (BankAccount, Event) => BankAccount = (state, event) =>
    event match {
      case BankAccountCreated(bankAccount) => bankAccount
      case BankAccountUpdated(amount) =>
        state.copy(balance = state.balance + amount)
    }

  def apply(id: String): Behavior[Command] = {
    EventSourcedBehavior[Command, Event, BankAccount](
      persistenceId = PersistenceId.ofUniqueId(id),
      emptyState = BankAccount(id, "", "", 0.0),
      commandHandler = commandHandler,
      eventHandler = eventHandler
    )
  }
}
