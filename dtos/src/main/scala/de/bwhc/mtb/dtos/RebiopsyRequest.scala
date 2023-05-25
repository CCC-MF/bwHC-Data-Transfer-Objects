package de.bwhc.mtb.dtos


import java.time.LocalDate

import play.api.libs.json.Json



final case class RebiopsyRequest
(
  id: RebiopsyRequest.Id,
  patient: Patient.Id,
  specimen: Specimen.Id,
  issuedOn: Option[LocalDate]
)


object RebiopsyRequest
{
  final case class Id(value: String) extends AnyVal

  implicit val formatId = Json.valueFormat[Id]

  implicit val format = Json.format[RebiopsyRequest]

}
