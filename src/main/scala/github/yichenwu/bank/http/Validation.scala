package github.yichenwu.bank.http
import cats.data.ValidatedNel
import cats.implicits._
import github.yichenwu.bank.http.ValidationFailure.{EmptyField, NegativeValue}

sealed trait ValidationFailure {
  def errorMessage: String
}
object ValidationFailure {
  final case class EmptyField(field: String) extends ValidationFailure {
    override def errorMessage: String = s"$field is empty"
  }
  final case class NegativeValue(field: String) extends ValidationFailure {
    override def errorMessage: String = s"$field is negative"
  }
}

object Validation {
  type ValidationResult[A] = ValidatedNel[ValidationFailure, A]

  implicit val requiredString: Required[String] = _.nonEmpty
  implicit val minimumBigDecimal: Minimum[BigDecimal] = _ >= _

  def validateRequiredField[A: Required](
      field: A,
      fieldName: String
  ): ValidationResult[A] = {
    val require = implicitly[Required[A]]
    if (require(field)) field.validNel
    else EmptyField(fieldName).invalidNel
  }

  def validateMinimumBigDecimal[A: Minimum](
      field: A,
      threshold: BigDecimal,
      fieldName: String
  ): ValidationResult[A] = {
    val minimum = implicitly[Minimum[A]]
    if (minimum(field, threshold)) field.validNel
    else if (threshold < 0) NegativeValue("threshold").invalidNel
    else NegativeValue(fieldName).invalidNel
  }

  trait Validator[A] {
    def validate(value: A): ValidationResult[A]
  }

  def validateEntity[A: Validator](entity: A): ValidationResult[A] =
    implicitly[Validator[A]].validate(entity)
}

trait Required[A] extends (A => Boolean)
trait Minimum[A] extends ((A, BigDecimal) => Boolean)
