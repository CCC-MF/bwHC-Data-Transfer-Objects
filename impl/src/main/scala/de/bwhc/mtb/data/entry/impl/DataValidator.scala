package de.bwhc.mtb.data.entry.impl


import java.time.LocalDate
import java.time.temporal.Temporal

import scala.util.Either
import scala.concurrent.{
  ExecutionContext,
  Future
}

import cats.data.NonEmptyList
import cats.data.Validated

import de.bwhc.util.spi._
import de.bwhc.util.data.Interval
import de.bwhc.util.data.Interval._
import de.bwhc.util.data.Validation._
import de.bwhc.util.data.Validation.dsl._

import de.bwhc.mtb.data.entry.dtos
import de.bwhc.mtb.data.entry.dtos._
import de.bwhc.mtb.data.entry.api.DataQualityReport

import de.bwhc.catalogs.icd
import de.bwhc.catalogs.icd._
import de.bwhc.catalogs.med.MedicationCatalog



trait DataValidator
{

  def check(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[Validated[DataQualityReport,MTBFile]]

}

trait DataValidatorProvider extends SPI[DataValidator]

object DataValidator extends SPILoader(classOf[DataValidatorProvider])




class DefaultDataValidator extends DataValidator
{

  import DefaultDataValidator._

  def check(
    mtbfile: MTBFile
  )(
    implicit ec: ExecutionContext
  ): Future[Validated[DataQualityReport,MTBFile]] = {
    Future.successful(
      mtbfile.validate
        .leftMap(DataQualityReport(mtbfile.patient.id,_))
    )
  }

}


object DefaultDataValidator
{

  import DataQualityReport._
  import DataQualityReport.Issue._

  import cats.syntax.apply._
  import cats.syntax.traverse._
  import cats.syntax.validated._
  import cats.instances.list._
  import cats.instances.option._
  import cats.instances.set._



  type DataQualityValidator[T] = Validator[DataQualityReport.Issue,T]


  implicit val patientValidator: DataQualityValidator[Patient] = {

    case pat @ Patient(Patient.Id(id),_,birthDate,_,insurance,dod) =>

      (
        birthDate mustBe defined otherwise (Error("Missing BirthDate") at Location("Patient",id,"birthdate")),

        insurance shouldBe defined otherwise (Warning("Missing Health Insurance") at Location("Patient",id,"insurance")),

        (dod couldBe defined
          otherwise (Info("Undefined date of death. Ensure if up to date") at Location("Patient",id,"dateOfDeath")))
          .andThen (
            _.get must be (before (LocalDate.now)) otherwise (Error("Invalid Date of death in the future") at Location("Patient",id,"dateOfDeath"))
          ),

        (birthDate, dod)
          .mapN(
            (b,d) => d must be (after (b))
                       otherwise (Error("Invalid Date of death before birthDate") at Location("Patient",id,"dateOfDeath"))
           )
          .getOrElse(LocalDate.now.validNel[Issue])
      )
      .mapN { case _: Product => pat }
  }


  implicit def consentValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[Consent] = {

    case consent @ Consent(id,patient,_) =>

      (patient must be (patId)
        otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("Consent",id.value,"patient")))
        .map(_ => consent)

  }


  implicit def episodeValidator(
    implicit patId: Patient.Id
  ): DataQualityValidator[MTBEpisode] = {
    case episode @ MTBEpisode(id,patient,period) =>

      (patient must be (patId)
        otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("MTBEpisode",id.value,"patient")))
        .map(_ => episode)

  }


  implicit lazy val icd10gmCatalog = ICD10GMCatalogs.getInstance.get

  implicit lazy val icdO3Catalog   = ICDO3Catalogs.getInstance.get


  implicit def icd10Validator(
    implicit
    catalog: ICD10GMCatalogs
  ): DataQualityValidator[Coding[ICD10GM]] = {

      case icd10 @ Coding(dtos.ICD10GM(code),_,version) =>

        version.map(
          v =>
            ifThrows(
              icd.ICD10GM.Version(v)
            )(
              Error(s"Invalid ICD-10-GM Version $version") at Location("ICD-10-GM Coding","","version")
            )
        )
        .getOrElse(icd.ICD10GM.Version(2019).validNel[Issue])
        .andThen (
          v =>
            code must be (in (catalog.codings(v).map(_.code.value)))
              otherwise (Error(s"Invalid ICD-10-GM code $code") at Location("ICD-10-GM Coding","","code"))
        )
        .map(c => icd10)

    }


  implicit def icdO3TValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3T]] = {

      case icdo3t @ Coding(ICDO3T(code),_,version) =>

        (version mustBe defined otherwise (Error("Missing ICD-O-3 Version") at Location("ICD-O-3-T Coding","","version")))
          .andThen(
            v =>           
              ifThrows(icd.ICDO3.Version(v.get))(Error(s"Invalid ICD-O-3 Version $version") at Location("ICD-O-3-T Coding","","version"))
          )
          .andThen(
            v =>
              code must be (in (catalog.topographyCodings(v).map(_.code.value)))
                otherwise (Error(s"Invalid ICD-O-3-T code $code") at Location("ICD-O-3-T Coding","","code"))
          )
          .map(c => icdo3t)

    }


  implicit def icdO3MValidator(
    implicit
    catalog: ICDO3Catalogs
  ): DataQualityValidator[Coding[ICDO3M]] = {

      case icdo3m @ Coding(ICDO3M(code),_,version) =>

        (version mustBe defined otherwise (Error("Missing ICD-O-3 Version") at Location("ICD-O-3-M Coding","","version")))
          .andThen(
            v =>
              ifThrows(icd.ICDO3.Version(v.get))(Error(s"Invalid ICD-O-3 Version $version") at Location("ICD-O-3-M Coding","","version"))
          )
          .andThen(
            v =>
              code must be (in (catalog.morphologyCodings(v).map(_.code.value)))
                otherwise (Error(s"Invalid ICD-O-3-M code $code") at Location("ICD-O-3-M Coding","","code"))
          )
          .map(c => icdo3m)

    }


  implicit val medicationCatalog = MedicationCatalog.getInstance.get

  implicit def medicationValidator(
    implicit
    catalog: MedicationCatalog
  ): DataQualityValidator[Coding[Medication]] = {

      case medication @ Coding(code,_,_) =>

        (code.value must be (in (catalog.entries.map(_.code.value)))
          otherwise (Error(s"Invalid ATC Medication code $code") at Location("Medication Coding","","code")))
         .map(c => medication)

    }


  implicit def diagnosisValidator(
    implicit
    patId: Patient.Id,
    specimenRefs: List[Specimen.Id],
    histologyRefs: List[HistologyReport.Id]
  ): DataQualityValidator[Diagnosis] = {

    case diag @ Diagnosis(Diagnosis.Id(id),patient,date,icd10,icdO3T,_,histologyReports,_,_) =>

      implicit val diagId = diag.id

      (
        (patient must be (patId) otherwise (
          Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("Diagnosis",id,"patient"))),

        (date ifUndefined (Warning("Missing Recording Date") at Location("Diagnosis",id,"recordedOn"))),

        (icd10 ifUndefined (Error("Missing ICD-10-GM Coding") at Location("Diagnosis",id,"icd10")))
          andThen (_ validate),

        (icdO3T ifUndefined (Info("Missing ICD-O-3-T Coding") at Location("Diagnosis",id,"icdO3T")))
          andThen (_ validate),

        histologyReports.map(
          refs => refs validateEach (
            ref => ref must be (in (histologyRefs))
              otherwise (Fatal(s"Invalid Reference to HistologyReport/${ref.value}") at Location("Diagnosis",id,"histologyReports"))
          )
        )
        .getOrElse(List.empty[HistologyReport.Id].validNel[Issue]) 
      )
      .mapN { case _: Product => diag}

  }


  implicit val therapyLines = (0 to 9).map(TherapyLine(_))
  
  implicit def prevGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    therapyLines: Seq[TherapyLine]
  ): DataQualityValidator[PreviousGuidelineTherapy] = {

    case th @ PreviousGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,medication) =>
      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("PreviousGuidelineTherapy",id,"patient"))),

        diag must be (in (diagnosisRefs))
          otherwise (Fatal(s"Invalid Reference to Diagnosis/${diag.value}") at Location("PreviousGuidelineTherapy",id,"diagnosis")),

        (therapyLine ifUndefined (Warning("Missing Therapy Line") at Location("PreviousGuidelineTherapy",id,"therapyLine")))
          andThen ( l =>
            l must be (in (therapyLines)) otherwise (Error(s"Invalid Therapy Line ${l.value}") at Location("PreviousGuidelineTherapy",id,"therapyLine"))
          ),

        medication.toList.validateEach
        
      )
      .mapN { case _: Product => th }
  }


  implicit def lastGuidelineTherapyValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    therapyLines: Seq[TherapyLine],
    therapyRefs: Seq[TherapyId],
  ): DataQualityValidator[LastGuidelineTherapy] = {

    case th @ LastGuidelineTherapy(TherapyId(id),patient,diag,therapyLine,period,medication,reasonStopped) =>
      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("LastGuidelineTherapy",id,"patient"))),

        diag must be (in (diagnosisRefs))
          otherwise (Fatal(s"Invalid Reference to Diagnosis/${diag.value}") at Location("LastGuidelineTherapy",id,"diagnosis")),

        (therapyLine ifUndefined (Warning("Missing Therapy Line") at Location("LastGuidelineTherapy",id,"therapyLine")))
          andThen ( l =>
            l must be (in (therapyLines)) otherwise (Error(s"Invalid Therapy Line ${l.value}") at Location("LastGuidelineTherapy",id,"therapyLine"))
          ),
        
        medication.toList.validateEach,

        (reasonStopped ifUndefined (Warning("Missing Stop Reason") at Location("LastGuidelineTherapy",id,"reasonStopped"))),

        (th.id must be (in (therapyRefs))
           otherwise (Warning("Missing Response") at Location("LastGuidelineTherapy",id,"response")))
      )
      .mapN { case _: Product => th }

  }


  implicit def ecogStatusValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[ECOGStatus] = {

    case pfSt @ ECOGStatus(id,patient,_,_) =>

      (patient must be (patId)
        otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("ECOGStatus",id.value,"patient")))
        .map(_ => pfSt)

  }


  implicit def specimenValidator(
    implicit
    patId: Patient.Id,
    icd10codes: Seq[ICD10GM]
  ): DataQualityValidator[Specimen] = {

    case sp @ Specimen(Specimen.Id(id),patient,icd10,typ,collection) =>
      (
        (patient must be (patId) otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("Specimen",id,"patient"))),

        icd10.validate
          andThen (
            icd => icd.code must be (in (icd10codes)) otherwise (Fatal(s"Invalid Reference to Diagnosis $icd") at Location("Specimen",id,"icd10"))
          ),
  
        (typ ifUndefined (Warning(s"Missing Specimen type") at Location("Specimen",id,"type"))),

        (collection ifUndefined (Warning(s"Missing Specimen collection") at Location("Specimen",id,"collection")))
       
      )
      .mapN { case _: Product => sp }

  }


  implicit def histologyReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReport] = {

    case histo @ HistologyReport(HistologyReport.Id(id),patient,specimen,date,morphology,tumorContent) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("HistologyReport",id,"patient"))),

        (specimen must be (in (specimens))
           otherwise (Fatal(s"Invalid Reference to Specimen/${specimen.value}") at Location("HistologyReport",id,"specimen"))),

        (date ifUndefined (Error("Missing issue date") at Location("HistologyReport",id,"issuedOn"))),

        (morphology ifUndefined (Error("Missing TumorMorphology") at Location("HistologyReport",id,"tumorMorphology")))
          andThen (
            m => m.value ifUndefined (Error("Missing ICD-O-3 M Coding") at Location("HistologyReport",id,"tumorMorphology"))
              andThen (_ validate)
          ),

        tumorContent ifUndefined (
          Error("Missing TumorCellContent") at Location("HistologyReport",id,"tumorCellContent")
        ) andThen ( tc =>
          (
            tc.method must be (TumorCellContent.Method.Histologic)
              otherwise (Error(s"Expected TumorCellContent method ${TumorCellContent.Method.Histologic}")
                at Location("HistologyReport",id,"tumorContent")),
            tc validate
          )
          .mapN { case _: Product => tc }
        ),
      )
      .mapN { case _: Product => histo }

  }


  implicit def molecularPathologyValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[MolecularPathologyFinding] = {

    case molPath @ MolecularPathologyFinding(MolecularPathologyFinding.Id(id),patient,specimen,_,date,_) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("MolecularPathologyFinding",id,"patient"))),

        (specimen must be (in (specimens))
           otherwise (Fatal(s"Invalid Reference to Specimen/${specimen.value}") at Location("MolecularPathologyFinding",id,"specimen"))),

        (date shouldBe defined otherwise (Error("Missing issue date") at Location("MolecularPathologyFinding",id,"issuedOn"))),

      )
      .mapN { case _: Product => molPath }

  }



  import scala.math.Ordering.Double.TotalOrdering

  implicit def tumorContentValidator: DataQualityValidator[TumorCellContent] = {

    case tc @ TumorCellContent(id,specimenId,method,value) => {

      val tcRange = Interval.Closed(0.0,1.0)

      (value must be (in (tcRange))
        otherwise (Error(s"Tumor content value $value not in reference range $tcRange") at Location("TumorContent",specimenId.value,"value")))
        .map(_ => tc)

    }
     
  }


  implicit def ngsReportValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[SomaticNGSReport] = {


    case ngs @
      SomaticNGSReport(SomaticNGSReport.Id(id),patient,specimen,date,_,_,tumorContent,brcaness,msi,tmb,_,_,_,_,_) => {

      import SomaticNGSReport._

      val brcanessRange = Interval.Closed(0.0,1.0)
      val msiRange      = Interval.Closed(0.0,2.0)
      val tmbRange      = Interval.Closed(0.0,1e6)  // TMB in mut/MBase, so [0,1000000]

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("SomaticNGSReport",id,"patient"))),

        (specimen must be (in (specimens))
           otherwise (Fatal(s"Invalid Reference to Specimen/${specimen.value}") at Location("SomaticNGSReport",id,"specimen"))),

/*
        (tumorContents.map(_.method) should contain (TumorCellContent.Method.Histologic)
          otherwise (Warning(s"Missing pathologic TumorCellContent finding")
            at Location("SomaticNGSReport",id,"tumorContent"))),
           
        (tumorContents.map(_.method) should contain (TumorCellContent.Method.Bioinformatic)
          otherwise (Warning(s"Missing bio-informatic TumorCellContent finding")
            at Location("SomaticNGSReport",id,"tumorContent"))),
         
        (tumorContents validateEach),
*/
        tumorContent.method must be (TumorCellContent.Method.Bioinformatic)
          otherwise (Error(s"Expected TumorCellContent method ${TumorCellContent.Method.Bioinformatic}")
            at Location("SomaticNGSReport",id,"tumorContent")),

        tumorContent validate,
       
        (brcaness shouldBe defined otherwise (Info("Missing BRCAness value") at Location("SomaticNGSReport",id,"brcaness")))
          .andThen(
            opt =>
              opt.get.value must be (in (brcanessRange)) otherwise (
                  Error(s"BRCAness value ${opt.get.value} not in reference range $brcanessRange")
                    at Location("SomaticNGSReport",id,"brcaness")
                )
              ),
             
        (msi shouldBe defined otherwise (Info("Missing MSI value") at Location("SomaticNGSReport",id,"msi")))
          .andThen(
            opt =>
              opt.get.value must be (in (msiRange)) otherwise (
                Error(s"MSI value ${opt.get.value} not in reference range $msiRange") at Location("SomaticNGSReport",id,"msi")
              )
            ),
             
        tmb.value must be (in (tmbRange))
          otherwise (Error(s"TMB value ${tmb.value} not in reference range $tmbRange") at Location("SomaticNGSReport",id,"tmb"))
             
      )
      .mapN { case _: Product => ngs }

    }

  }



  implicit def carePlanValidator(
    implicit
    patId: Patient.Id,
    diagnosisRefs: List[Diagnosis.Id],
    recommendationRefs: Seq[TherapyRecommendation.Id],
    counsellingRequestRefs: Seq[GeneticCounsellingRequest.Id],
    rebiopsyRequestRefs: Seq[RebiopsyRequest.Id]
  ): DataQualityValidator[CarePlan] = {

    case cp @ CarePlan(CarePlan.Id(id),patient,diag,date,_,noTarget,recommendations,counsellingReq,rebiopsyRequests) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("CarePlan",id,"patient"))),

        diag must be (in (diagnosisRefs))
          otherwise (Fatal(s"Invalid Reference to Diagnosis/${diag.value}") at Location("CarePlan",id,"diagnosis")),

        (date ifUndefined (Warning("Missing Recording Date") at Location("CarePlan",id,"issuedOn"))),

        recommendations ifUndefined (Error("Missing Therapy Recommendations") at Location("CarePlan",id,"recommendations"))
          andThen (
            recs => recs validateEach (
              ref => ref must be (in (recommendationRefs))
                otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${ref.value}") at Location("CarePlan",id,"recommendations"))
            )
          ),

        counsellingReq.map(
          ref => ref must be (in (counsellingRequestRefs))
           otherwise (Fatal(s"Invalid Reference to GeneticCounsellingRequest/${ref.value}") at Location("CarePlan",id,"geneticCounsellingRequest"))
        )
        .getOrElse(None.validNel[Issue]),
 
        rebiopsyRequests.map( refs =>
          refs validateEach (
            ref => ref must be (in (rebiopsyRequestRefs))
              otherwise (Fatal(s"Invalid Reference to RebiopsyRequest/${ref.value}") at Location("CarePlan",id,"rebiopsyRequests"))
          )
        )
        .getOrElse(List.empty[RebiopsyRequest.Id].validNel[Issue]) 
      )
      .mapN { case _: Product => cp }

  }

 
  implicit def recommendationValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[TherapyRecommendation] = {

    case rec @ TherapyRecommendation(TherapyRecommendation.Id(id),patient,diag,date,medication,priority,loe,variant) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("TherapyRecommendation",id,"patient"))),

        (date ifUndefined (Warning("Missing Recording Date") at Location("TherapyRecommendation",id,"issuedOn"))),

        (medication validateEach),

        (priority ifUndefined (Warning("Missing Priority") at Location("TherapyRecommendation",id,"priority"))),

        (loe ifUndefined (Warning("Missing Level of Evidence") at Location("TherapyRecommendation",id,"levelOfEvidence"))),

      )
      .mapN { case _: Product => rec }

  }


  implicit def counsellingRequestValidator(
    implicit
    patId: Patient.Id
  ): DataQualityValidator[GeneticCounsellingRequest] = {

    case req @ GeneticCounsellingRequest(GeneticCounsellingRequest.Id(id),patient,date,_) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("GeneticCounsellingRequest",id,"patient"))),

        (date ifUndefined (Warning("Missing Recording Date") at Location("GeneticCounsellingRequest",id,"issuedOn"))),

      )
      .mapN { case _: Product => req }

  }


  implicit def rebiopsyRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[RebiopsyRequest] = {

    case req @ RebiopsyRequest(RebiopsyRequest.Id(id),patient,specimen,date) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("RebiopsyRequest",id,"patient"))),

        (date ifUndefined (Warning("Missing Recording Date") at Location("RebiopsyRequest",id,"issuedOn"))),

        (specimen must be (in (specimens))
           otherwise (Fatal(s"Invalid Reference to Specimen/${specimen.value}") at Location("RebiopsyRequest",id,"specimen"))),
      )
      .mapN { case _: Product => req }

  }


  implicit def histologyReevaluationRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[HistologyReevaluationRequest] = {

    case req @ HistologyReevaluationRequest(HistologyReevaluationRequest.Id(id),patient,specimen,date) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("HistologyReevaluationRequest",id,"patient"))),

        (date ifUndefined (Warning("Missing Recording Date") at Location("HistologyReevaluationRequest",id,"issuedOn"))),

        (specimen must be (in (specimens))
           otherwise (Fatal(s"Invalid Reference to Specimen/${specimen.value}") at Location("HistologyReevaluationRequest",id,"specimen"))),
      )
      .mapN { case _: Product => req }

  }



  private val nctNumRegex = """(NCT\d{8})""".r

  implicit def studyInclusionRequestValidator(
    implicit
    patId: Patient.Id,
    specimens: Seq[Specimen.Id]
  ): DataQualityValidator[StudyInclusionRequest] = {

    case req @ StudyInclusionRequest(StudyInclusionRequest.Id(id),patient,diag,NCTNumber(nct),date) =>

      (
        (patient must be (patId) otherwise (
          Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("StudyInclusionRequest",id,"patient"))),

        (nct must matchRegex (nctNumRegex) otherwise (
          Error(s"Invalid NCT Number pattern ${nct}") at Location("StudyInclusionRequest",id,"nctNumber"))),  

        (date ifUndefined (Warning("Missing Recording Date") at Location("StudyInclusionRequest",id,"issuedOn"))),

      )
      .mapN { case _: Product => req }

  }



  implicit def claimValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id],
  ): DataQualityValidator[Claim] = {

    case cl @ Claim(Claim.Id(id),patient,_,therapy) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("Claim",id,"patient"))),

        (therapy must be (in (recommendationRefs))
          otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${therapy.value}") at Location("Claim",id,"therapy")))

      )
      .mapN { case _: Product => cl }

  }


  implicit def claimResponseValidator(
    implicit
    patId: Patient.Id,
    claimRefs: Seq[Claim.Id],
  ): DataQualityValidator[ClaimResponse] = {

    case cl @ ClaimResponse(ClaimResponse.Id(id),claim,patient,_,_,reason) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}") at Location("ClaimResponse",id,"patient"))),

        (claim must be (in (claimRefs))
          otherwise (Fatal(s"Invalid Reference to Claim/${claim.value}") at Location("ClaimResponse",id,"claim"))),

        (reason ifUndefined (Warning("Missing Reason for ClaimResponse Status") at Location("ClaimResponse",id,"reason")))
      )
      .mapN { case _: Product => cl }

  }


  implicit def molecularTherapyValidator(
    implicit
    patId: Patient.Id,
    recommendationRefs: Seq[TherapyRecommendation.Id]
  ): DataQualityValidator[MolecularTherapy] = {

    case th @ NotDoneTherapy(TherapyId(id),patient,recordedOn,basedOn,notDoneReason,note) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}")
             at Location("MolecularTherapy",id,"patient"))),

        (basedOn must be (in (recommendationRefs))
          otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${basedOn.value}")
            at Location("MolecularTherapy",id,"basedOn")))

      )
      .mapN { case _: Product => th }


    case th @ StoppedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_,_) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}")
             at Location("MolecularTherapy",id,"patient"))),

        (basedOn must be (in (recommendationRefs))
          otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${basedOn.value}")
            at Location("MolecularTherapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }


    case th @ CompletedTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}")
             at Location("MolecularTherapy",id,"patient"))),

        (basedOn must be (in (recommendationRefs))
          otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${basedOn.value}")
            at Location("MolecularTherapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }


    case th @ OngoingTherapy(TherapyId(id),patient,_,basedOn,_,medication,_,_) =>

      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}")
             at Location("MolecularTherapy",id,"patient"))),

        (basedOn must be (in (recommendationRefs))
          otherwise (Fatal(s"Invalid Reference to TherapyRecommendation/${basedOn.value}")
            at Location("MolecularTherapy",id,"basedOn"))),

        medication.toList.validateEach
      )
      .mapN { case _: Product => th }

  }


  implicit def reponseValidator(
    implicit
    patId: Patient.Id,
    therapyRefs: Seq[TherapyId]
  ): DataQualityValidator[Response] = {

    case resp @ Response(Response.Id(id),patient,therapy,_,_) =>
      (
        (patient must be (patId)
           otherwise (Fatal(s"Invalid Reference to Patient/${patient.value}")
             at Location("Response",id,"patient"))),

        (therapy must be (in (therapyRefs))
          otherwise (Fatal(s"Invalid Reference to Therapy/${therapy.value}")
            at Location("Response",id,"therapy")))
      )
      .mapN{ case _: Product => resp }

  }



  implicit val mtbFileValidator: DataQualityValidator[MTBFile] = {

    case mtbfile @ MTBFile(
      patient,
      consent,
      episode,
      diagnoses,
      _,
      previousGuidelineTherapies,
      lastGuidelineTherapy,
      ecogStatus,
      specimens,
      molPathoFindings,
      histologyReports,
      ngsReports,
      carePlans,
      recommendations,
      counsellingRequests,
      rebiopsyRequests,
      histologyReevaluationRequests,
      studyInclusionRequests,
      claims,
      claimResponses,
      molecularTherapies,
      responses
    ) =>

    implicit val patId = patient.id  

    consent.status match {

      case Consent.Status.Rejected => {
        (
          patient.validate,

          consent.validate,

          episode.validate,

          diagnoses mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"diagnoses")),

          previousGuidelineTherapies mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"previousGuidelineTherapies")),

          lastGuidelineTherapy mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"lastGuidelineTherapy")),

          ecogStatus mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ecogStatus")),

          specimens mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"specimens")),

          histologyReports mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"histologyReports")),

          ngsReports mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"ngsReports")),

          carePlans mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"carePlans")),

          recommendations mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"recommendations")),

          counsellingRequests mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"counsellingRequests")),

          rebiopsyRequests mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"rebiopsyRequests")),

          claims mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claims")),

          claimResponses mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"claimResponses")),

          molecularTherapies mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"molecularTherapies")),

          responses mustBe undefined otherwise (
            Fatal(s"Data must not be defined for Consent '${consent.status}'")
              at Location("MTBFile",patId.value,"responses")),
        )
        .mapN { case _: Product => mtbfile }
      }



      case Consent.Status.Active => {
        
        implicit val diagnosisRefs =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.id)

        implicit val icd10codes =
          diagnoses.getOrElse(List.empty[Diagnosis])
            .map(_.icd10)
            .filter(_.isDefined)
            .map(_.get.code)
  
        implicit val histoRefs =
          histologyReports.getOrElse(List.empty[HistologyReport]).map(_.id)
  
        implicit val specimenRefs =
          specimens.getOrElse(List.empty[Specimen]).map(_.id)
  
        implicit val recommendationRefs =
          recommendations.getOrElse(List.empty[TherapyRecommendation]).map(_.id)
  
        implicit val counsellingRequestRefs =
          counsellingRequests.getOrElse(List.empty[GeneticCounsellingRequest]).map(_.id)
  
        implicit val rebiopsyRequestRefs =
          rebiopsyRequests.getOrElse(List.empty[RebiopsyRequest]).map(_.id)
  
        implicit val claimRefs =
          claims.getOrElse(List.empty[Claim]).map(_.id)
 
        // Get List of TherapyIds as combined IDs of Previous and Last Guideline Therapies and Molecular Therapies
        implicit val therapyRefs =
          previousGuidelineTherapies.map(_.map(_.id)).getOrElse(List.empty[TherapyId]) ++
          lastGuidelineTherapy.map(_.id) ++
          molecularTherapies.map(_.flatMap(_.history.map(_.id))).getOrElse(List.empty[TherapyId])
  
  
        (
          patient.validate,
          consent.validate,
          episode.validate,
  
          (diagnoses ifUndefined (Error("Missing diagnosis records") at Location("MTBFile",patId.value,"diagnoses")))
            andThen (_ ifEmpty (Error("Missing diagnoses records") at Location("MTBFile",patId.value,"diagnoses")))
            andThen (_ validateEach),
  
          (previousGuidelineTherapies ifUndefined (Warning("Missing previous Guideline Therapies") at Location("MTBFile",patId.value,"previousGuidelineTherapies")))
            andThen (_ ifEmpty (Warning("Missing previous Guideline Therapies") at Location("MTBFile",patId.value,"previousGuidelineTherapies")))
            andThen (_ validateEach),
  
          (lastGuidelineTherapy ifUndefined (Error("Missing last Guideline Therapy") at Location("MTBFile",patId.value,"lastGuidelineTherapies")))
            andThen (_ validate),
  
          (ecogStatus ifUndefined (Warning("Missing ECOG Performance Status records") at Location("MTBFile",patId.value,"ecogStatus")))
            andThen (_ ifEmpty (Warning("Missing ECOG Performance Status records") at Location("MTBFile",patId.value,"ecogStatus")))
            andThen (_ validateEach),
  
          (specimens ifUndefined (Warning("Missing Specimen records") at Location("MTBFile",patId.value,"specimens")))
            andThen (_ ifEmpty (Warning("Missing Specimen records") at Location("MTBFile",patId.value,"specimens")))
            andThen (_ validateEach),
  
          (histologyReports ifUndefined (Warning("Missing HistologyReport records") at Location("MTBFile",patId.value,"histologyReports")))
            andThen (_ ifEmpty (Warning("Missing HistologyReport records") at Location("MTBFile",patId.value,"histologyReports")))
            andThen (_ validateEach),
  
          (molPathoFindings ifUndefined (Warning("Missing MolecularPathology records") at Location("MTBFile",patId.value,"molecularPathologyFindings")))
            andThen (_ ifEmpty (Warning("Missing MolecularPathology records") at Location("MTBFile",patId.value,"molecularPathologyFindings")))
            andThen (_ validateEach),
  
          (ngsReports ifUndefined (Warning("Missing SomaticNGSReport records") at Location("MTBFile",patId.value,"ngsReports")))
            andThen (_ ifEmpty (Warning("Missing SomaticNGSReport records") at Location("MTBFile",patId.value,"ngsReports")))
            andThen (_ validateEach),
  
          (carePlans ifUndefined (Warning("Missing CarePlan records") at Location("MTBFile",patId.value,"carePlans")))
            andThen (_ ifEmpty (Warning("Missing CarePlan records") at Location("MTBFile",patId.value,"carePlans")))
            andThen (_ validateEach),
  
          (recommendations ifUndefined (Warning("Missing TherapyRecommendation records") at Location("MTBFile",patId.value,"recommendations")))
            andThen (_ ifEmpty (Warning("Missing TherapyRecommendation records") at Location("MTBFile",patId.value,"recommendations")))
            andThen (_ validateEach),
  
          counsellingRequests.map(_ validateEach)
            .getOrElse(List.empty[GeneticCounsellingRequest].validNel[Issue]),
  
          rebiopsyRequests.map(_ validateEach)
            .getOrElse(List.empty[RebiopsyRequest].validNel[Issue]),
  
          histologyReevaluationRequests.map(_ validateEach)
            .getOrElse(List.empty[HistologyReevaluationRequest].validNel[Issue]),
  
          studyInclusionRequests.map(_ validateEach)
            .getOrElse(List.empty[StudyInclusionRequest].validNel[Issue]),
  
          (claims ifUndefined (Warning("Missing Insurance Claim records") at Location("MTBFile",patId.value,"claims")))
            andThen (_ ifEmpty (Warning("Missing Insurance Claim records") at Location("MTBFile",patId.value,"claims")))
            andThen (_ validateEach),
  
          (claimResponses ifUndefined (Warning("Missing ClaimResponse records") at Location("MTBFile",patId.value,"claimResponses")))
            andThen (_ ifEmpty (Warning("Missing ClaimResponse records") at Location("MTBFile",patId.value,"claimResponses")))
            andThen (_ validateEach),
  
          (molecularTherapies ifUndefined (Warning("Missing MolecularTherapy records") at Location("MTBFile",patId.value,"molecularTherapies")))
            andThen (_ ifEmpty (Warning("Missing MolecularTherapy records") at Location("MTBFile",patId.value,"molecularTherapies")))
            andThen (_.flatMap(_.history) validateEach),
  
          //TODO: validate Responses
  
        )
        .mapN { case _: Product => mtbfile }

      }

    }

  }

}
