package github.yichenwu.bank.http
import akka.actor.typed.scaladsl.AskPattern.Askable
import akka.actor.typed.{ActorRef, ActorSystem, Scheduler}
import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.model.headers.Location
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.util.Timeout
import cats.data.Validated._
import cats.implicits._
import de.heikoseeberger.akkahttpcirce.FailFastCirceSupport._
import github.yichenwu.bank.actors.PersistentBankAccount.Command._
import github.yichenwu.bank.actors.PersistentBankAccount.Response._
import github.yichenwu.bank.actors.PersistentBankAccount.{Command, Response}
import github.yichenwu.bank.http.Validation._
import io.circe.generic.auto._

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

sealed trait IncomingHttpRequest
final case class UpdateBankDetailRequest(
    currency: String,
    amount: BigDecimal
) extends IncomingHttpRequest
object UpdateBankDetailRequest {
  implicit val validate: Validator[UpdateBankDetailRequest] =
    (value: UpdateBankDetailRequest) => {
      val requiredFields = validateRequiredField(value.currency, "currency")
      val minimum = validateMinimumBigDecimal(value.amount, 1.0, "amount")
      (requiredFields, minimum).mapN(UpdateBankDetailRequest.apply)
    }
}

final case class CreateBankRequest(
    user: String,
    currency: String,
    initialBalance: BigDecimal
) extends IncomingHttpRequest
object CreateBankRequest {
  implicit val validate: Validator[CreateBankRequest] =
    (value: CreateBankRequest) => {
      val userValidation = validateRequiredField(value.user, "user")
      val currencyValidation = validateRequiredField(value.currency, "currency")
      val balanceValidation = validateMinimumBigDecimal(
        value.initialBalance,
        1.90,
        "initial balance"
      )
      (userValidation, currencyValidation, balanceValidation).mapN(
        CreateBankRequest.apply
      )
    }
}

class BankRouter(bank: ActorRef[Command])(implicit
    actorSystem: ActorSystem[_]
) {
  implicit val timeout: Timeout = Timeout(2 seconds)
  implicit val scheduler: Scheduler = actorSystem.scheduler

  def getBankDetails(id: String): Future[Response] = {
    bank.ask(GetBankAccount(id, _))
  }

  def updateBankDetails(
      id: String,
      request: UpdateBankDetailRequest
  ): Future[Response] = {
    bank.ask(UpdateBankAccount(id, request.currency, request.amount, _))
  }

  def createBank(request: CreateBankRequest): Future[Response] =
    bank.ask(
      CreateBankAccount(
        request.user,
        request.currency,
        request.initialBalance,
        _
      )
    )

  def validateRequest[R: Validator](
      request: R
  )(outBound: Route): Route = {
    validateEntity(request) match {
      case Valid(_) => outBound
      case Invalid(e) =>
        complete(
          StatusCodes.BadRequest,
          FailureResponse(e.toList.map(_.errorMessage).mkString(","))
        )
    }
  }
  /*
    1. Get  localhost:9090/bank/id
        Response 200 with bank details
    2. Post localhost:9090/bank/ json payload
        Response 201 with header location
    3. Put localhost:9090/bank/ json payload
        Response 404
   */
  val routes: Route =
    pathPrefix("bank") {
      path(Segment) { id =>
        get {
          onSuccess(getBankDetails(id)) {
            case GetBankAccountResponse(Some(account)) =>
              complete(account)
            case GetBankAccountResponse(None) =>
              complete(
                StatusCodes.NotFound,
                FailureResponse(s"Bank account $id cannot be found")
              )
          }
        } ~
          put {
            entity(as[UpdateBankDetailRequest]) { request =>
              validateRequest(request) {
                onSuccess(updateBankDetails(id, request)) {
                  case BankAccountUpdatedResponse(Some(account)) =>
                    complete(account)
                  case BankAccountUpdatedResponse(None) =>
                    complete(
                      StatusCodes.NotFound,
                      FailureResponse(s"Bank account $id cannot be found")
                    )
                }
              }
            }
          }
      } ~
        pathEndOrSingleSlash {
          post {
            entity(as[CreateBankRequest]) { request =>
              validateRequest(request) {
                onSuccess(createBank(request)) {
                  case BankAccountCreatedResponse(id) =>
                    respondWithHeader(Location(s"/bank/$id")) {
                      complete(StatusCodes.Created)
                    }
                }
              }
            }
          }

        }
    }

}
