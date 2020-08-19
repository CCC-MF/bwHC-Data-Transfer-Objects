package de.bwhc.mtb.data.entry.impl


import java.time.Instant

import scala.util._

import scala.concurrent.{
  ExecutionContext,
  Future
}

import cats.data.NonEmptyList
import cats.data.Validated._

import cats.instances.future._
import cats.syntax.apply._
import cats.syntax.either._

import de.bwhc.util.Logging

import de.bwhc.mtb.data.entry.api._

import de.bwhc.mtb.data.entry.dtos._



class MTBDataServiceProviderImpl extends MTBDataServiceProvider
{
    
  def getInstance: MTBDataService = {

    val localSite    = Option(System.getProperty("bwhc.zpm.site")).map(ZPM(_)).get  //TODO: improve configurability

    val db           = MTBDataDB.getInstance.get

    val queryService = QueryServiceProxy.getInstance.get
    
    val validator    = DataValidator.getInstance.getOrElse(new DefaultDataValidator)

    new MTBDataServiceImpl(
      localSite,
      validator,
      db,
      queryService
    )
  }
    
}


object Helpers
{

  implicit class DataQualityReportOps(val qc: DataQualityReport) extends AnyVal
  {

    def hasFatalErrors: Boolean = {
      qc.issues
        .map(_.severity)
        .toList
        .contains(DataQualityReport.Issue.Severity.Fatal)
    }

    def hasErrors: Boolean = {
      qc.issues
        .map(_.severity)
        .toList
        .exists(s =>
          s == DataQualityReport.Issue.Severity.Fatal ||
          s == DataQualityReport.Issue.Severity.Error
        )
    }

    def hasOnlyInfos: Boolean = {
      val severities =
        qc.issues
          .map(_.severity)
          .toList
          .toSet 

      (severities & (DataQualityReport.Issue.Severity.values - DataQualityReport.Issue.Severity.Info)).isEmpty
    }
  }

}


class MTBDataServiceImpl
(
  private val localSite: ZPM,
  private val validator: DataValidator,
  private val db: MTBDataDB,
  private val queryService: QueryServiceProxy
)
extends MTBDataService
with Logging
{

  import Helpers._

  def process(
    cmd: MTBDataService.Command
  )(
    implicit ec: ExecutionContext
  ): Future[Either[MTBDataService.Error,MTBDataService.Response]] = {

    import MTBDataService.Command._
    import MTBDataService.Response._
    import MTBDataService.Error._

    cmd match {

      //-----------------------------------------------------------------------
      case Upload(data) => {

        log.info(s"Handling MTBFile upload for Patient ${data.patient.id.value}")

        //: Assign managingZPM to Patient
        val mtbfile = data.copy(patient = data.patient.copy(managingZPM = Some(localSite)))       

        val result =
          for {
            checked <- validator check mtbfile

            response <-

              checked match {

                case Invalid(qcReport) if (qcReport.hasFatalErrors) =>
                  Future successful InvalidData(qcReport).asLeft[MTBDataService.Response]

                case Invalid(qcReport) if (qcReport.hasOnlyInfos) => {
  
                  log.info(s"Only 'Info' issues detected, forwarding data to QueryService")
  
                  (queryService ! QueryServiceProxy.Command.Upload(mtbfile))
                    .andThen {
                      case Success(_) => db.deleteAll(mtbfile.patient.id)
                    }
                    .map(_ => Imported(mtbfile).asRight[MTBDataService.Error])
                }
  
                case Invalid(qcReport) => {

                  log.info(s"Non-fatal issues detected, storing DataQualityReport")

                  (
                    db.save(mtbfile),
                    db.save(qcReport)
                  )
                  .mapN(
                    (_,qc) => qc
                  )
                  .andThen {
                    case Success(qc) if (!qc.hasErrors) => {
                      log.info(s"No 'Error' level issues present, forwarding data to QueryService")
                      queryService ! QueryServiceProxy.Command.Upload(mtbfile)
                    }
                  }
                  .map(IssuesDetected(_).asRight[MTBDataService.Error])

                }

                case Valid(_) => {
  
                  log.info(s"No issues detected, forwarding data to QueryService")
  
                  (queryService ! QueryServiceProxy.Command.Upload(mtbfile))
                    .andThen {
                      case Success(_) => db.deleteAll(mtbfile.patient.id)
                    }
                    .map(_ => Imported(mtbfile).asRight[MTBDataService.Error])
                }
  
              }
  
          } yield response

        result.recover {
          case t => UnspecificError(t.getMessage).asLeft[MTBDataService.Response]
        }

      }

      //-----------------------------------------------------------------------
      case Delete(patId) => {

        log.info(s"Handling Delete request for data of $patId")

        (
          db.deleteAll(patId),
          queryService ! QueryServiceProxy.Command.Delete(patId)
        )
        .mapN(
          (_,_) => Deleted(patId).asRight[MTBDataService.Error]
        )
        .recover {
          case t => UnspecificError(t.getMessage).asLeft[MTBDataService.Response]
        }
      }
    }

  }


  def patientsWithIncompleteData(
    implicit ec: ExecutionContext
  ): Future[Iterable[Patient]] = {
  
    db.mtbfiles.map(_.map(_.patient))  

  }


  def mtbfile(
    patient: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[MTBFile]] = {
    db.mtbfile(patient)
  }


  def dataQualityReport(
    patient: Patient.Id
  )(
    implicit ec: ExecutionContext
  ): Future[Option[DataQualityReport]] = {

    db.dataQcReportOf(patient)

  }


}
