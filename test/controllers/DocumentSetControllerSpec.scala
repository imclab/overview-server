/*
 * DocumentSetControllerSpec.scala
 * 
 * Overview Project
 * Created by Jonas Karlsson, June 2012
 */
package controllers

import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.Scope
import play.api.Play.{ start, stop }
import play.api.test.{ FakeApplication, FakeRequest }
import play.api.test.Helpers._

import org.overviewproject.tree.Ownership
import controllers.auth.AuthorizedRequest
import controllers.forms.DocumentSetForm.Credentials
import models.orm.{ DocumentSet, DocumentSetUser }
import models.orm.DocumentSetType._
import models.{OverviewUser,OverviewDocumentSet}
import models.ResultPage

class DocumentSetControllerSpec extends Specification with Mockito {
  step(start(FakeApplication()))

  class TestDocumentSetController extends DocumentSetController {
    var savedDocumentSet: Option[DocumentSet] = None
    var createdJobOwnerId: Option[Long] = None

    var documentSets: Map[Long, DocumentSet] = Map((1l, DocumentSet(DocumentCloudDocumentSet, 1l, "title", Some("query"))))

    override protected def loadDocumentSetCreationJobs(userEmail: String, pageSize: Int, pageNum: Int) = {
      ResultPage(Seq(), 10, 1)
    }

    override protected def loadDocumentSets(userEmail: String, pageSize: Int, page: Int) = {
      ResultPage(Seq(documentSets(1L)), 10, 1).map(OverviewDocumentSet.apply(_: DocumentSet))
    }

    override protected def loadDocumentSet(id: Long): Option[DocumentSet] = {
      documentSets.get(id)
    }
    override protected def saveDocumentSet(documentSet: DocumentSet): DocumentSet = {
      savedDocumentSet = Some(documentSet.copy(id = 1l))
      savedDocumentSet.get
    }

    override protected def insertOrUpdateDocumentSetUser(dsu: DocumentSetUser) = {}

    override protected def createDocumentSetCreationJob(documentSet: DocumentSet, credentials: Credentials): Unit = createdJobOwnerId = Some(documentSet.id)
  }

  trait ControllerScope extends Scope {
    val controller = new TestDocumentSetController
    val user = mock[OverviewUser]
  }

  trait AuthorizedSession extends ControllerScope {
    val request = new AuthorizedRequest(
      FakeRequest()
        .withSession("AUTH_USER_ID" -> user.id.toString)
        .withFormUrlEncodedBody(sessionForm: _*), user)

    val sessionForm: Seq[(String, String)]
  }

  class CreateRequest extends {
    val query = "documentSet query"
    val title = "documentSet title"
    override val sessionForm = Seq("query" -> query, "title" -> title)
  } with AuthorizedSession 

  class UpdateRequest extends {
    val documentSetId: Long = 1l
    val newTitle = "New Title"
    override val sessionForm = Seq("public" -> "true", "title" -> newTitle)
  } with AuthorizedSession

  class BadUpdateRequest extends {
    val documentSetId: Long = 1l
    override val sessionForm = Seq("public" -> "not a boolean")
  } with AuthorizedSession 
  
  class ViewerRequest extends {
    val documentSetId: Long = 1l
    val email = "user@host.com"
    override val sessionForm = Seq("email" -> email, "role" -> "Viewer")
  } with AuthorizedSession 
    

  class BadViewerRequest extends {
    val badEmail = "bad email format"
    override val sessionForm = Seq("email" -> badEmail, "role" -> "Viewer")
  } with AuthorizedSession
  
  "The DocumentSet Controller" should {

    "submit a DocumentSetCreationJob when a new query is received" in new CreateRequest {
      val result = controller.create()(request)
      controller.savedDocumentSet must beSome
      val documentSet = controller.savedDocumentSet.get
      documentSet.query must beSome
      documentSet.query.get must be equalTo (query)
      documentSet.title must be equalTo (title)

      controller.createdJobOwnerId must beSome
      controller.createdJobOwnerId.get must be equalTo (documentSet.id)
    }

    "redirect to documentsets view" in new CreateRequest {
      val result = controller.create()(request)
      redirectLocation(result).getOrElse("No redirect") must be equalTo ("/documentsets")
    }

    "update the DocumentSet" in new UpdateRequest {
      val result = controller.update(documentSetId)(request)

      status(result) must be equalTo (OK)
      controller.savedDocumentSet must beSome
      val documentSet = controller.savedDocumentSet.get

      documentSet.isPublic must beTrue
      documentSet.title must be equalTo (newTitle)
    }

    "return NotFound if document set is bad" in new UpdateRequest {
      val result = controller.update(-1l)(request)
      status(result) must be equalTo (NOT_FOUND)
    }

    "return BadRequest if form input is bad" in new BadUpdateRequest {
      val result = controller.update(documentSetId)(request)
      status(result) must be equalTo (BAD_REQUEST)
    }
  }

  step(stop)
}
