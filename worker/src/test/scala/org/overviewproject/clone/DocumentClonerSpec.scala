package org.overviewproject.clone

import org.overviewproject.database.DB
import org.overviewproject.persistence.DocumentSetIdGenerator
import org.overviewproject.persistence.orm.Schema
import org.overviewproject.postgres.LO
import org.overviewproject.test.DbSpecification
import org.overviewproject.tree.orm.{ Document, DocumentSet, File, UploadedFile }
import org.overviewproject.tree.orm.Page

class DocumentClonerSpec extends DbSpecification {
  step(setupDb)

  "DocumentCloner" should {

    trait CloneContext extends DbTestContext {
      import org.overviewproject.postgres.SquerylEntrypoint._
      var documentSetId: Long = _
      var documentSetCloneId: Long = _
      var expectedCloneData: Seq[(Long, String)] = _
      var documentIdMapping: Map[Long, Long] = _
      var sourceDocuments: Seq[Document] = _
      var clonedDocuments: Seq[Document] = _
      var ids: DocumentSetIdGenerator = _

      def createCsvImportDocumentSet: Long = {
        val uploadedFile = Schema.uploadedFiles.insert(UploadedFile("content-disposition", "content-type", 100))
        val documentSet = Schema.documentSets.insert(DocumentSet(title = "DocumentClonerSpec", uploadedFileId = Some(uploadedFile.id)))

        documentSet.id
      }

      def documents: Seq[Document] = Seq.tabulate(10)(i =>
        Document(documentSetId, text = Some("text-" + i), id = ids.next))

      override def setupWithDb = {
        documentSetId = createCsvImportDocumentSet
        documentSetCloneId = createCsvImportDocumentSet
        ids = new DocumentSetIdGenerator(documentSetId)

        Schema.documents.insert(documents)
        sourceDocuments = Schema.documents.where(d => d.documentSetId === documentSetId).toSeq

        expectedCloneData = sourceDocuments.map(d => (documentSetCloneId, d.text.get))

        DocumentCloner.clone(documentSetId, documentSetCloneId)
        clonedDocuments = Schema.documents.where(d => d.documentSetId === documentSetCloneId).toSeq

      }
    }

    trait DocumentsWithLargeIds extends CloneContext {
      override def documents: Seq[Document] = Seq(Document(documentSetId, text = Some("text"), id = ids.next + 0xFFFFFFFAl))
    }

    trait PdfUploadContext extends DbTestContext {
      import org.overviewproject.postgres.SquerylEntrypoint._
      var documentSetId: Long = _
      var documentSetCloneId: Long = _

      protected val ContentLength = 100l

      override def setupWithDb = {
        val documentSet = Schema.documentSets.insertOrUpdate(DocumentSet(title = "PDF upload"))
        val documentSetClone = Schema.documentSets.insertOrUpdate(DocumentSet(title = "Clone"))

        val ids = new DocumentSetIdGenerator(documentSet.id)
        val oid = createContents
        val file = Schema.files.insertOrUpdate(File(1, oid, "name"))
        Schema.documents.insert(
          Document(
            documentSet.id,
            text = Some("text"),
            fileId = Some(file.id),
            pageId = createPage(file.id),
            contentLength = Some(ContentLength),
            id = ids.next))

        documentSetId = documentSet.id
        documentSetCloneId = documentSetClone.id
      }

      protected def createPage(fileId: Long): Option[Long] = None

      private def createContents: Long = {
        implicit val pgConnection = DB.pgConnection

        LO.withLargeObject(_.oid)
      }

      protected def findFiles: Iterable[File] = {
        from(Schema.files)(f =>
          where(f.id in
            from(Schema.documents)(d =>
              where(d.documentSetId === documentSetCloneId)
                select (d.fileId)))
            select (f))
      }


      protected def findClonedDocuments: Iterable[Document] =
        from(Schema.documents)(d =>
          where(d.documentSetId === documentSetCloneId)
            select (d))
    }

    trait SplitPdfUploadContext extends PdfUploadContext {
      import org.overviewproject.postgres.SquerylEntrypoint._

      override def createPage(fileId: Long): Option[Long] = {
        val pageData = Array.fill[Byte](128)(0xFF.toByte)
        val page = Schema.pages.insertOrUpdate(Page(fileId, 1, pageData, 1))
        Some(page.id)
      }
      
      protected def findPages: Iterable[Page] = {
        from(Schema.pages)(p =>
          where(p.id in
            from(Schema.documents)(d =>
              where(d.documentSetId === documentSetCloneId)
                select (d.pageId)))
            select (p))
      }
    }

    "Create document clones" in new CloneContext {
      val clonedData = clonedDocuments.map(d => (d.documentSetId, d.text.get))
      clonedData must containTheSameElementsAs(expectedCloneData)
    }

    "Create clones with ids matching source ids" in new CloneContext {
      val sourceIndeces = sourceDocuments.map(d => (d.id << 32) >> 32)
      val cloneIndeces = clonedDocuments.map(d => (d.id << 32) >> 32)

      sourceIndeces must containTheSameElementsAs(cloneIndeces)
    }

    "create clones with documentSetId encoded in id" in new DocumentsWithLargeIds {
      val highOrderBits = clonedDocuments.map(_.id >> 32)

      highOrderBits.distinct must beEqualTo(Seq(documentSetCloneId))
    }

    "increase refcount on files" in new PdfUploadContext {
      DocumentCloner.clone(documentSetId, documentSetCloneId)

      val file = findFiles.headOption

      file must beSome.like {
        case f => f.referenceCount must be equalTo (2)
      }

      val document = findClonedDocuments.headOption

      document must beSome.like {
        case d => d.contentLength must beSome(ContentLength)
      }
    }

    "increase refcount on pages" in new SplitPdfUploadContext {
      DocumentCloner.clone(documentSetId, documentSetCloneId)

      val page = findPages.headOption
      page must beSome.like {
        case p => p.referenceCount must be equalTo (2)
      }
    }
  }
  step(shutdownDb)
}
