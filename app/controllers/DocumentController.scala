package controllers

import java.io.InputStream
import play.api.mvc.Controller
import controllers.auth.AuthorizedAction
import controllers.auth.Authorities.userOwningDocument
import models.orm.finders.DocumentFinder
import models.OverviewDocument
import play.api.libs.iteratee.Enumerator
import play.api.mvc.SimpleResult
import play.api.mvc.ResponseHeader
import org.overviewproject.postgres.LargeObjectInputStream
import controllers.util.PlayLargeObjectInputStream
import models.orm.finders.FileFinder

trait DocumentController extends Controller {
  trait Storage {
    def find(id: Long): Option[OverviewDocument]
    def contentStream(oid: Long): Option[InputStream]
  }

  def showJson(documentId: Long) = AuthorizedAction(userOwningDocument(documentId)) { implicit request =>
    storage.find(documentId) match {
      case Some(document) => Ok(views.json.Document.show(document))
      case None => NotFound
    }
  }

  def show(documentId: Long) = AuthorizedAction(userOwningDocument(documentId)) { implicit request =>
    storage.find(documentId) match {
      case Some(document) => Ok(views.html.Document.show(document))
      case None => NotFound
    }
  }

  def contents(documentId: Long, fileId: Long) = AuthorizedAction(userOwningDocument(documentId)) { implicit request =>
    val result = for {
      document <- storage.find(documentId)
      data <- storage.contentStream(fileId)
    } yield {
      val dataContent = Enumerator.fromStream(data)(play.api.libs.concurrent.Execution.Implicits.defaultContext)
      val filename = document.title.getOrElse("UploadedFile.pdf")
      SimpleResult(
        header = ResponseHeader(OK, Map(
          CONTENT_LENGTH -> s"${document.contentLength.get}",
          CONTENT_TYPE -> "application/pdf",
          CONTENT_DISPOSITION -> s"""inline ; filename="$filename"""")),
        body = dataContent)
    }

    result.getOrElse(NotFound)
  }

  val storage: DocumentController.Storage
}

object DocumentController extends DocumentController {
  object DatabaseStorage extends DocumentController.Storage {

    override def find(id: Long) = {
      DocumentFinder.byId(id).headOption.map(OverviewDocument.apply)
    }

    override def contentStream(fileId: Long): Option[InputStream] = for {
      file <- FileFinder.byId(fileId).headOption
    } yield { new PlayLargeObjectInputStream(file.contentsOid) }
  }

  override val storage = DatabaseStorage
}
