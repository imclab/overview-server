package models

import java.sql.Timestamp
import org.specs2.specification.Scope
import play.api.Play.{ start, stop }
import play.api.test.FakeApplication

import org.overviewproject.test.IdGenerator._
import org.overviewproject.test.Specification
import org.overviewproject.tree.orm.UploadedFile
import org.overviewproject.tree.Ownership
import helpers.DbTestContext
import models.orm.DocumentSet
import models.orm.DocumentSetType._
import models.upload.OverviewUploadedFile
import models.orm.Schema
import models.orm.finders.{ DocumentSetUserFinder, DocumentSetCreationJobFinder }
import models.orm.DocumentSetUser

class OverviewDocumentSetSpec extends Specification {
  step(start(FakeApplication()))

  "OverviewDocumentSet" should {
    trait OneDocumentSet {
      def throwWrongType = throw new Exception("Wrong DocumentSet type")
      def ormDocumentSet: DocumentSet

      lazy val documentSet = OverviewDocumentSet(ormDocumentSet)
    }

    trait CsvImportDocumentSetScope extends Scope with OneDocumentSet {
      val ormUploadedFile = UploadedFile(
        id = 0L,
        uploadedAt = new java.sql.Timestamp(new java.util.Date().getTime()),
        contentDisposition = "attachment; filename=foo.csv",
        contentType = "text/csv; charset=latin1",
        size = 0L)

      val title = "Title"
      val createdAt = new java.util.Date()
      val count = 10

      override def ormDocumentSet = DocumentSet(
        CsvImportDocumentSet,
        title = title,
        createdAt = new java.sql.Timestamp(createdAt.getTime()),
        uploadedFile = Some(ormUploadedFile))
    }

    trait DocumentCloudDocumentSetScope extends Scope with OneDocumentSet {
      val title = "Title"
      val query = "Query"
      val createdAt = new java.util.Date()
      val count = 10

      override def ormDocumentSet = DocumentSet(
        DocumentCloudDocumentSet,
        title = title,
        query = Some(query),
        createdAt = new java.sql.Timestamp(createdAt.getTime()),
        documentCount = count)
    }

    "apply() should generate a CsvImportDocumentSet" in new CsvImportDocumentSetScope {
      documentSet must beAnInstanceOf[OverviewDocumentSet.CsvImportDocumentSet]
    }

    "apply() should generate a DocumentCloudDocumentSet" in new DocumentCloudDocumentSetScope {
      documentSet must beAnInstanceOf[OverviewDocumentSet.DocumentCloudDocumentSet]
    }

    "createdAt should point to the ORM document" in new CsvImportDocumentSetScope {
      documentSet.createdAt.getTime must beEqualTo(createdAt.getTime)
    }

    "title should be the title" in new DocumentCloudDocumentSetScope {
      documentSet.title must beEqualTo(title)
    }

    "documentCount should be the document count" in new DocumentCloudDocumentSetScope {
      documentSet.documentCount must beEqualTo(count)
    }

    "CSV document sets must have an uploadedFile" in new CsvImportDocumentSetScope {
      documentSet match {
        case csvDs: OverviewDocumentSet.CsvImportDocumentSet => {
          csvDs.uploadedFile must beSome
        }
        case _ => throwWrongType
      }
    }

    "DC document sets must have a query" in new DocumentCloudDocumentSetScope {
      documentSet match {
        case dcDs: OverviewDocumentSet.DocumentCloudDocumentSet => {
          dcDs.query must beEqualTo(query)
        }
        case _ => throwWrongType
      }
    }

    "have isPublic value" in new CsvImportDocumentSetScope {
      documentSet.isPublic must beFalse
    }
  }

  "OverviewDocumentSet database operations" should {

    import anorm.SQL
    import anorm.SqlParser._
    import org.overviewproject.postgres.SquerylEntrypoint._
    import models.orm.Schema._
    import models.orm.{ DocumentSet, LogEntry, Tag, User }
    import models.orm.DocumentSetType._
    import org.overviewproject.postgres.LO
    import org.overviewproject.tree.orm.{ Document, Node, DocumentProcessingError, DocumentSetCreationJob, DocumentTag }
    import org.overviewproject.tree.orm.DocumentSetCreationJobState._
    import org.overviewproject.tree.orm.DocumentSetCreationJobType._
    import org.overviewproject.tree.orm.DocumentType._
    import helpers.PgConnectionContext

    trait DocumentSetWithUserScope extends DbTestContext {
      var admin: User = _
      var ormDocumentSet: DocumentSet = _
      var documentSet: OverviewDocumentSet = _
      // Will become cleaner when OverviewDocumentSet is cleared up
      override def setupWithDb = {
        super.setupWithDb
        admin = User.findById(1l).getOrElse(throw new Exception("Missing admin user from db"))
        ormDocumentSet = admin.createDocumentSet("query").save
        documentSet = OverviewDocumentSet(ormDocumentSet)
      }
    }

    trait DocumentSetWithUpload extends PgConnectionContext {
      var documentSet: OverviewDocumentSet = _
      var ormDocumentSet: DocumentSet = _
      var oid: Long = _

      override def setupWithDb = {
        super.setupWithDb
        LO.withLargeObject { largeObject =>
          val uploadedFile = uploadedFiles.insertOrUpdate(UploadedFile(contentDisposition = "disposition", contentType = "type", size = 0l))
          ormDocumentSet = DocumentSet(CsvImportDocumentSet, title = "title", uploadedFileId = Some(uploadedFile.id)).save
          documentSet = OverviewDocumentSet(ormDocumentSet)
          oid = largeObject.oid
        }
      }
    }

    trait DocumentSetWithCompletedUpload extends PgConnectionContext {
      var documentSet: OverviewDocumentSet = _
      var uploadedFile: UploadedFile = _

      override def setupWithDb = {
        super.setupWithDb
        uploadedFile = uploadedFiles.insertOrUpdate(UploadedFile(contentDisposition = "disposition", contentType = "type", size = 0l))
        val ormDocumentSet = DocumentSet(CsvImportDocumentSet, title = "title", uploadedFileId = Some(uploadedFile.id)).save

        documentSet = OverviewDocumentSet(ormDocumentSet)
      }
    }

    trait DocumentSetReferencedByOtherTables extends DocumentSetWithUserScope {
      var oid: Long = _

      override def setupWithDb = {
        super.setupWithDb
        val uploadedFile = uploadedFiles.insertOrUpdate(UploadedFile(contentDisposition = "disposition", contentType = "type", size = 0l))
        ormDocumentSet = DocumentSet(
          DocumentCloudDocumentSet,
          title = "title",
          query = Some("query"),
          documentProcessingErrorCount=1
        ).save

        documentSet = OverviewDocumentSet(ormDocumentSet)
        LogEntry(
          documentSetId = documentSet.id,
          userId = 1l,
          date = new Timestamp(0),
          component = "test"
        ).save
        val document = documents.insert(Document(
          CsvImportDocument,
          documentSet.id,
          text = Some("test doc"),
          id = nextDocumentId(documentSet.id)
        ))
        
        val documentProcessingError = documentProcessingErrors.insertOrUpdate(DocumentProcessingError(documentSet.id, "url", "message"))

        val tag = Tag(documentSetId = documentSet.id, name = "tag").save
        val node = nodes.insertOrUpdate(Node(documentSet.id, None, "description", 10, Array.empty))
        documentTags.insertOrUpdate(DocumentTag(document.id, tag.id))

        SQL("INSERT INTO node_document (node_id, document_id) VALUES ({n}, {d})").on("n" -> node.id, "d" -> document.id).executeInsert()
      }
    }

    trait DocumentSetCreationInProgress extends DocumentSetReferencedByOtherTables {
      override def setupWithDb = {
        super.setupWithDb
        documentSetCreationJobs.insertOrUpdate(DocumentSetCreationJob(documentSet.id, DocumentCloudJob, state = InProgress))
      }
    }

    trait DocumentSetCreationNotStarted extends DocumentSetReferencedByOtherTables {
      override def setupWithDb = {
        super.setupWithDb
        documentSetCreationJobs.insertOrUpdate(DocumentSetCreationJob(documentSet.id, DocumentCloudJob, state = NotStarted))
      }
    }

    trait DocumentSetCreationCancelled extends DocumentSetReferencedByOtherTables {
      override def setupWithDb = {
        super.setupWithDb
        documentSetCreationJobs.insertOrUpdate(DocumentSetCreationJob(documentSet.id, DocumentCloudJob, state = Cancelled))
      }
    }

    trait PublicDocumentSet extends DbTestContext {
      var documentSet: OverviewDocumentSet = _

      override def setupWithDb = {
        super.setupWithDb
        val ormDocumentSet = DocumentSet(DocumentCloudDocumentSet, query = Some("public"), isPublic = true).save

        documentSet = OverviewDocumentSet(ormDocumentSet)
      }
    }

    trait PublicAndPrivateDocumentSets extends DbTestContext {
      override def setupWithDb = {
        val privateDocumentSets = Seq.fill(5)(DocumentSet(DocumentCloudDocumentSet, query = Some("private")))
        val publicDocumentSets = Seq.fill(5)(DocumentSet(DocumentCloudDocumentSet, query = Some("public"), isPublic = true))

        documentSets.insert(privateDocumentSets ++ publicDocumentSets)
      }
    }

    trait PublicDocumentSetBeingCloned extends PublicDocumentSet {

      var cloneDocumentSet: DocumentSet = _

      override def setupWithDb = {
        super.setupWithDb
        val admin = User.findById(1l).getOrElse(throw new Exception("Missing admin user from db"))
        cloneDocumentSet = admin.createDocumentSet("query").save

        val cloneJob = DocumentSetCreationJob(cloneDocumentSet.id, CloneJob, sourceDocumentSetId = Some(documentSet.id))
        documentSetCreationJobs.insert(cloneJob)
      }
    }

    "delete document set and all associated information" in new DocumentSetReferencedByOtherTables {
      val job: DocumentSetCreationJob = ormDocumentSet.createDocumentSetCreationJob()
      documentSetCreationJobs.insertOrUpdate(job.copy(state = Error))

      documentSetUsers.allRows must have size (1)
      OverviewDocumentSet.delete(documentSet.id)

      logEntries.allRows must have size (0)
      tags.allRows must have size (0)
      documentTags.allRows must have size (0)
      documentSetUsers.allRows must have size (0)
      documents.allRows must have size (0)
      nodes.allRows must have size (0)
      documentSetCreationJobs.allRows must have size (0)
      documentProcessingErrors.allRows must have size 0

      SQL("SELECT * FROM node_document").as(long("node_id") ~ long("document_id") map flatten *) must have size (0)
      OverviewDocumentSet.findById(documentSet.id) must beNone
    }.pendingUntilFixed("Something's in the database...")

    "delete Uploaded file and LargeObject" in new DocumentSetWithUpload {
      val job: DocumentSetCreationJob = ormDocumentSet.createDocumentSetCreationJob(contentsOid = Some(oid))
      documentSetCreationJobs.insertOrUpdate(job.copy(state = Error))

      OverviewDocumentSet.delete(documentSet.id)

      uploadedFiles.allRows must have size (0)
      LO.withLargeObject(oid) { lo => } must throwA[Exception]
    }

    "do not try to delete LargeObject if upload is complete" in new DocumentSetWithCompletedUpload {
      OverviewDocumentSet.delete(documentSet.id)

      uploadedFiles.allRows must have size (0)
    }

    "Delete job, document set and all associated information if job not started" in new DocumentSetCreationNotStarted {
      OverviewDocumentSet.delete(documentSet.id)
      logEntries.allRows must have size (0)
      tags.allRows must have size (0)
      documentTags.allRows must have size (0)
      documentSetUsers.allRows must have size (0)
      documents.allRows must have size (0)
      nodes.allRows must have size (0)
      documentSetCreationJobs.allRows must have size (0)

      SQL("SELECT * FROM node_document").as(long("node_id") ~ long("document_id") map flatten *) must have size (0)

      val job = OverviewDocumentSetCreationJob.findByDocumentSetId(documentSet.id)
      job must beNone
    }.pendingUntilFixed("Stuff remains in the database")

    "cancel job and delete client generated information only if job in progress" in new DocumentSetCreationInProgress {
      OverviewDocumentSet.delete(documentSet.id)
      logEntries.allRows must have size (0)
      tags.allRows must have size (0)
      documentTags.allRows must have size (0)
      documentSetUsers.allRows must have size (0)
      documents.allRows must have size (1)
      nodes.allRows must have size (1)
      documentSetCreationJobs.allRows must have size (1)
      documentProcessingErrors.allRows must have size 1

      SQL("SELECT * FROM node_document").as(long("node_id") ~ long("document_id") map flatten *) must have size (1)
      val job = OverviewDocumentSetCreationJob.findByDocumentSetId(documentSet.id)
      job must beSome
      job.get.state must be equalTo (Cancelled)
    }.pendingUntilFixed("Stuff remains in the database")

    "cancel job and delete client generated information only if job cancelled" in new DocumentSetCreationCancelled {
      OverviewDocumentSet.delete(documentSet.id)
      logEntries.allRows must have size (0)
      tags.allRows must have size (0)
      documentTags.allRows must have size (0)
      documentSetUsers.allRows must have size (0)
      documents.allRows must have size (1)
      nodes.allRows must have size (1)
      documentSetCreationJobs.allRows must have size (1)
      documentProcessingErrors.allRows must have size 1

      SQL("SELECT * FROM node_document").as(long("node_id") ~ long("document_id") map flatten *) must have size (1)
      val job = OverviewDocumentSetCreationJob.findByDocumentSetId(documentSet.id)
      job must beSome
      job.get.state must be equalTo (Cancelled)
    }.pendingUntilFixed("Stuff remains in the database")

    "return error count" in new DocumentSetReferencedByOtherTables {
      documentSet.documentProcessingErrorCount must be equalTo (1)
    }

    inExample("create a copy for cloning user") in new DocumentSetWithUserScope {
      val cloner = User(email = "cloner@clo.ne", passwordHash = "password").save
      val documentSetClone = documentSet.cloneForUser(cloner.email)

      DocumentSetUserFinder.byDocumentSet(documentSetClone.id).headOption.map(_.userEmail) must beSome(cloner.email)
      documentSetClone.query must be equalTo (documentSet.query)
      documentSetClone.createdAt must be greaterThan (documentSet.createdAt)
    }

    "set cloned DocumentSet to be private" in new PublicDocumentSet {
      val cloner = User(email = "cloner@clo.ne", passwordHash = "password").save
      val documentSetClone = documentSet.cloneForUser(cloner.email)

      documentSetClone.isPublic must beFalse
    }

    // Need inExample to avoid compiler error
    inExample("copy uploaded_file when cloning CsvImportDocumentSet") in new DocumentSetWithCompletedUpload {
      val cloner = User(email = "cloner@clo.ne", passwordHash = "password").save

      val documentSetClone = documentSet.cloneForUser(cloner.email)

      val cloneWithUpload = OverviewDocumentSet.findById(documentSetClone.id).get

      cloneWithUpload match {
        case d: OverviewDocumentSet.CsvImportDocumentSet =>
          d.uploadedFile must beSome
          d.uploadedFile.get.id mustNotEqual (uploadedFile.id)
        case _ => failure("cloned document set is wrong type")
      }
    }

    inExample("create clone job for clone") in new DocumentSetWithUserScope {
      val cloner = User(email = "cloner@clo.ne", passwordHash = "password").save
      val documentSetClone = documentSet.cloneForUser(cloner.email)

      DocumentSetCreationJobFinder.byDocumentSet(documentSetClone.id).headOption must beSome
    }

    inExample("find all public document sets") in new PublicAndPrivateDocumentSets {
      val publicDocumentSets = OverviewDocumentSet.findPublic

      publicDocumentSets must have size (5)
      publicDocumentSets.map(_.query).toSeq.distinct must be equalTo (Seq("public"))
    }

    "cancel clone jobs when deleting source document set" in new PublicDocumentSetBeingCloned {
      OverviewDocumentSet.delete(documentSet.id)
      val cancelledCloneJob = OverviewDocumentSetCreationJob.findByDocumentSetId(cloneDocumentSet.id).get
      cancelledCloneJob.state must be equalTo (Cancelled)
      documentSetUsers.allRows must have size (0)
    }
  }
  step(stop)
}
