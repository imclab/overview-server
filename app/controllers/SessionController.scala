package controllers

import play.api.mvc.{AnyContent,Controller,Request}

import controllers.auth.{OptionallyAuthorizedAction,AuthResults}
import controllers.auth.Authorities.anyUser
import controllers.util.TransactionAction
import models.orm.Session
import models.orm.stores.SessionStore

trait SessionController extends Controller {
  val loginForm = controllers.forms.LoginForm()
  val registrationForm = controllers.forms.UserForm()

  private val m = views.Magic.scopedMessages("controllers.SessionController")

  trait Storage {
    def createSession(session: Session) : Unit
    def deleteSession(session: Session) : Unit
  }

  protected val storage : SessionController.Storage

  def new_() = OptionallyAuthorizedAction(anyUser) { implicit request =>
    request.user match {
      case Some(user) => Redirect(routes.WelcomeController.show)
      case _ => Ok(views.html.Session.new_(loginForm, registrationForm))
    }
  }

  def delete = OptionallyAuthorizedAction(anyUser) { implicit request =>
    request.userSession.foreach(storage.deleteSession)
    AuthResults.logoutSucceeded(request).flashing(
      "success" -> m("delete.success"),
      "event" -> "session-delete"
    )
  }

  def create = TransactionAction { implicit request =>
    loginForm.bindFromRequest.fold(
      formWithErrors => BadRequest(views.html.Session.new_(formWithErrors, registrationForm)),
      user => {
        val session = Session(user.id, request.remoteAddress)
        storage.createSession(session)
        AuthResults.loginSucceeded(request, session).flashing(
          "event" -> "session-create"
        )
      }
    )
  }
}

object SessionController extends SessionController {
  object DatabaseStorage extends Storage {
    override def createSession(session: Session) = SessionStore.insertOrUpdate(session)
    override def deleteSession(session: Session) = {
      import org.overviewproject.postgres.SquerylEntrypoint._
      SessionStore.delete(session.id)
    }
  }

  override val storage = DatabaseStorage
}
