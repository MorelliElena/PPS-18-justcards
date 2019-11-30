package org.justcards.server.user_manager

import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.duration._
import org.justcards.commons._
import org.justcards.server.Commons.UserInfo

import scala.util.Success

/**
  * Actor that manages all the users in the system
  * @param knowledgeEngine the system knowledgeEngine
  */
class UserManager(private val knowledgeEngine: ActorRef) extends Actor {

  import UserManagerMessage._
  import org.justcards.commons.AppError._
  import context.dispatcher
  private implicit val timeout = Timeout(3 seconds)
  private val playerManager = context.actorOf(PlayerManager())
  private val lobbyManager = context.actorOf(LobbyManager(knowledgeEngine))

  override def receive: Receive = {
    case msg: LogIn => playerManager ! UserLogIn(msg, sender())
    case LogOut(username) => playerManager ! UserLogout(username, sender())
    case LogOutAndExitFromLobby(username, lobbyId) =>
      playerManager ! UserLogout(username, sender())
      lobbyManager ! UserExitFromLobby(lobbyId, UserInfo(username, sender()))
    case msg: UserExitFromLobby => lobbyManager.askAndInformUser(msg)(sender())
    case msg: RetrieveAvailableGames => knowledgeEngine.askAndInformUser(msg)(sender())
    case _: RetrieveAvailableLobbies =>
      val user = sender()
      checkLogInAnd(user) { _ => 
        lobbyManager ! GetLobbies(user)
      }
    case msg: CreateLobby =>
      val user = sender()
      checkLogInAnd(user) { username =>
        lobbyManager ! UserCreateLobby(msg, UserInfo(username, user))
      }
    case msg: JoinLobby =>
      val user = sender()
      checkLogInAnd(user) { username =>
        lobbyManager ! UserJoinLobby(msg, UserInfo(username, user))
      }
    case _: Players =>
    case msg: UserManagerMessage => playerManager ! msg
  }

  private def checkLogInAnd(user: ActorRef)(onComplete: String => Unit) : Unit =
    playerManager ? PlayerLogged(user) onComplete {
      case Success(PlayerLoggedResult(true, username)) => onComplete(username)
      case _ => user ! ErrorOccurred(USER_NOT_LOGGED)
    }

  private implicit class RichActorRef(actorRef: ActorRef) {
    def askAndInformUser(msg: Any)(user: ActorRef): Unit = {
      actorRef ? msg onComplete {
        case Success(response) => user ! response
        case _ => user ! ErrorOccurred(MESSAGE_SENDING_FAILED)
      }
    }
  }

}

object UserManager {
  def apply(knowledgeEngine: ActorRef): Props = Props(classOf[UserManager], knowledgeEngine)
}

private[user_manager] object UserManagerMessage {

  sealed trait UserManagerMessage

  case class UserLogIn(message: LogIn, user: ActorRef) extends UserManagerMessage
  case class UserLogout(username: String, user: ActorRef) extends UserManagerMessage
  case class LogOutAndExitFromLobby(username: String, lobbyId: LobbyId) extends  UserManagerMessage

  case class RetrieveAllPlayers(sender: ActorRef) extends UserManagerMessage
  case class Players(players: Set[UserInfo]) extends UserManagerMessage
  case class PlayerLogged(user: ActorRef) extends UserManagerMessage
  case class PlayerLoggedResult(found: Boolean, username: String) extends UserManagerMessage

  case class GetLobbies(sender: ActorRef) extends UserManagerMessage
  case class UserCreateLobby(message: CreateLobby, user: UserInfo) extends UserManagerMessage
  case class UserJoinLobby(message: JoinLobby, user: UserInfo) extends UserManagerMessage
  case class UserExitFromLobby(lobbyId: LobbyId, userInfo: UserInfo) extends UserManagerMessage
  case class UserRemoved(removed: Boolean) extends UserManagerMessage

}
