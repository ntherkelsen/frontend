package controllers

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.util.Timeout
import authentication.AuthenticationSupport
import common.ExecutionContexts
import conf.Configuration
import model.Cached.RevalidatableResult
import model.{Cached, Cors}
import models.NewsAlertNotification
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.BodyParsers.parse.{json => BodyJson}
import play.api.mvc._
import services.breakingnews._

import scala.concurrent.duration._

class NewsAlertController(breakingNewsApi: BreakingNewsApi)(actorSystem: ActorSystem) extends Controller with AuthenticationSupport with ExecutionContexts {

  lazy val apiKey: String = Configuration.NewsAlert.apiKey.getOrElse(
    throw new RuntimeException("News Alert API Key not set")
  )

  override def validApiKey(key: String): Boolean = {
    key == apiKey
  }

  // Actor is useful here to prevent race condition
  // when accessing or updating the content of Breaking News
  // since actor's mailbox acts as a queue
  lazy val breakingNewsUpdater = actorSystem.actorOf(BreakingNewsUpdater.props(breakingNewsApi))
  implicit val actorTimeout = Timeout(30.seconds)

  case class NewsAlertError(error: String)
  implicit private val ew = Json.writes[NewsAlertError]

  def alerts() = Action.async { implicit request =>
    (breakingNewsUpdater ? GetAlertsRequest).mapTo[Option[JsValue]].map {
      case Some(json) => Cors(Cached(30)(RevalidatableResult.Ok(json)))
      case None => NoContent
    }.recover{
      case _ => InternalServerError(Json.toJson(NewsAlertError("Error while accessing alerts")))
    }
  }

  def create() : Action[NewsAlertNotification] = AuthenticatedAction.async(BodyJson[NewsAlertNotification]) { request =>
    val receivedNotification : NewsAlertNotification = request.body
    val result = breakingNewsUpdater ? NewNotificationRequest(receivedNotification)
    result.mapTo[NewsAlertNotification].map {
      case createdNotification => Created(Json.toJson(createdNotification))
    }.recover{
      case _ => InternalServerError(Json.toJson(NewsAlertError("Error while creating new notification")))
    }
  }
}
