package org.justcards.server.session_manager

import akka.actor.{Actor, ActorContext, ActorLogging, ActorRef, Props, Timers}

import scala.concurrent.duration._
import org.justcards.commons._
import org.justcards.server.Commons._
import org.justcards.server.knowledge_engine.game_knowledge.GameKnowledge
import org.justcards.server.user_manager.Lobby


/**
 * Actor that manages a game session
 */
class SessionManager(val gameKnowledge: GameKnowledge, var teams: Map[Team.Value,TeamPoints], lobby: LobbyId, players: Map[String, UserInfo]) extends Actor with Timers with ActorLogging{
  import Team._
  import org.justcards.commons.AppError._
  import SessionManager._
  import SessionManagerMessage._

  self ! StartMatch(None)

  var firstPlayerMatch: String = _

  override def receive: Receive = preMatchBehaviour

  private def preMatchBehaviour: Receive = {
    case StartMatch(firstPlayer) if firstPlayer.isEmpty || (players contains firstPlayer.get) =>
      val gameBoard = GameBoard(gameKnowledge, teams(TEAM_1) players, teams(TEAM_2) players, firstPlayer.map(players(_)))
      firstPlayerMatch = gameBoard.optionTurnPlayer get
      val newTeams: List[(UserId, TeamId)] = gameBoard.turn.map(user => (
        UserId(1, user),
        TeamId(teams.filterKeys(teams(_).players.contains(players(user))).head._1 toString)
      ))
      this broadcast GameStarted(newTeams)
      sendGameBoardInformation(gameBoard, players.keySet.toList)

      gameKnowledge hasToChooseBriscola match {
        case BriscolaSetting.NOT_BRISCOLA =>
          context toMatch(gameBoard, firstPlayerMatch)
        case BriscolaSetting.SYSTEM =>
          val briscolaSeed = if (gameBoard.optionLastCardDeck.isDefined) {
            gameBoard.optionLastCardDeck.get.seed
          } else {
            gameBoard.handCardsOf(firstPlayerMatch).head.seed
          }
          gameKnowledge setBriscola briscolaSeed
          this broadcast CorrectBriscola(briscolaSeed)
          context toMatch(gameBoard,firstPlayerMatch)
        case BriscolaSetting.USER =>
          context become chooseBriscolaPhase(gameBoard, firstPlayerMatch)
          players(firstPlayerMatch).userRef ! ChooseBriscola(gameKnowledge.seeds, TIMEOUT_TO_USER)
          timers startSingleTimer(TimeoutId, Timeout, TIMEOUT seconds)
      }
  }

  private def chooseBriscolaPhase(gameBoard: GameBoard, firstPlayer: String): Receive = {
    case Briscola(seed) if sender() == players(firstPlayer).userRef =>
      if (gameKnowledge setBriscola seed) {
        timers cancel TimeoutId
        this broadcast CorrectBriscola(seed)
        context toMatch(gameBoard, firstPlayer)
      } else {
        players(firstPlayer).userRef ! ErrorOccurred(BRISCOLA_NOT_VALID)
      }
    case Timeout | TimeoutExceeded(_) =>
      val briscolaSeed = if (gameBoard.handCardsOf(firstPlayer).nonEmpty)
        gameBoard.handCardsOf(firstPlayer).head.seed
      else
        gameBoard.optionLastCardDeck.get.seed
      this broadcast CorrectBriscola(briscolaSeed)
      gameKnowledge setBriscola briscolaSeed
      context toMatch(gameBoard, firstPlayer)
  }

  private def inMatch(gameBoard: GameBoard): Receive = {
    case Play(card) if playable(gameBoard, card) && isCorrectPlayer(gameBoard, sender()) =>
      if(sender() != self) sender() ! Played(card)
      else players(gameBoard.optionTurnPlayer.get).userRef ! Played(card)
      timers cancel TimeoutId
      val newGameBoard: GameBoard = gameBoard playerPlays card
      if (newGameBoard.optionTurnPlayer.nonEmpty) context toMatch(newGameBoard, newGameBoard.optionTurnPlayer.get)
      else handleEndHand(newGameBoard)
    case Play(_) =>
      sender() ! ErrorOccurred(CARD_NOT_VALID)
    case Timeout | TimeoutExceeded(_) =>
      val playableCards: Set[Card] = for (card <- gameBoard.handCardsOfTurnPlayer.get if playable(gameBoard, card)) yield card
      self ! Play(playableCards.head)
  }

  private def handleEndHand(gameBoard: GameBoard): Unit = {
    sendGameBoardInformation(gameBoard, players.keySet.toList)
    val playerWinner = gameKnowledge.handWinner(gameBoard.fieldCards.map(elem => (elem._1, players(elem._2))))
    this broadcast HandWinner(playerWinner)
    var newGameBoard = gameBoard handWinner playerWinner
    newGameBoard = newGameBoard.draw match {
      case None => newGameBoard
      case Some(x) => x
    }
    context toMatch(newGameBoard, newGameBoard.optionTurnPlayer.get)
  }

  private def endMatch(gameBoard: GameBoard): Receive = {
    case endMatchMessage(lastHandWinner: String) =>
      var tookCardsTeam: Map[Team.Value, Set[Card]] = teams.keySet.map((_, Set[Card]())).toMap
      for (team <- Team.values; player <- teams(team).players) {
        tookCardsTeam = tookCardsTeam + (team -> (tookCardsTeam(team) ++ gameBoard.tookCardsOf(player)))
      }
      val lastHandWinnerTeam = teams.find(_._2.players contains players(lastHandWinner)).get._1
      val matchPoints = gameKnowledge.matchPoints(tookCardsTeam(TEAM_1), tookCardsTeam(TEAM_2), lastHandWinnerTeam)
      val pointsForSession = gameKnowledge.matchWinner(tookCardsTeam(TEAM_1), tookCardsTeam(TEAM_2), lastHandWinnerTeam)
      teams = teams + (TEAM_1 -> TeamPoints(teams(TEAM_1).players, teams(TEAM_1).points + pointsForSession._2))
      teams = teams + (TEAM_2 -> TeamPoints(teams(TEAM_2).players, teams(TEAM_2).points + pointsForSession._3))
      broadcast(MatchWinner(pointsForSession._1, (matchPoints._1, matchPoints._2), (teams(TEAM_1).points, teams(TEAM_2).points)))
      gameKnowledge.sessionWinner(teams(TEAM_1).points, teams(TEAM_2).points) match {
        case None =>
          context become preMatchBehaviour
          self ! StartMatch((gameBoard playerAfter firstPlayerMatch).map(players(_)))
        case Some(winner) =>
          this broadcast GameWinner(winner)
          this broadcast OutOfLobby(lobby)
          context stop self
      }

  }

  private def turn(gameBoard: GameBoard, turnPlayer: String): Unit = {
    if (gameBoard.handCardsOfTurnPlayer.get.isEmpty) {
      context become endMatch(gameBoard)
      self ! endMatchMessage(gameBoard.optionTurnPlayer.get)
    } else {
      sendGameBoardInformation(gameBoard, players.keySet filter (_!=turnPlayer) toList)
      players(turnPlayer).userRef ! Turn(gameBoard handCardsOf turnPlayer, gameBoard.fieldCards.map(_._1), TIMEOUT_TO_USER)
      timers startSingleTimer(TimeoutId, Timeout, TIMEOUT seconds)
    }
  }

  private def isCorrectPlayer(gameBoard: GameBoard, sender: ActorRef): Boolean =
    sender == players(gameBoard.optionTurnPlayer.get).userRef || sender == self

  private def playable(gameBoard: GameBoard, card: Card): Boolean =
      gameKnowledge.play(card, gameBoard.fieldCards.map(_._1), gameBoard.handCardsOfTurnPlayer.get).isDefined

  private def sendGameBoardInformation(gameBoard: GameBoard, receivers: List[String]): Unit =
    for (player <- receivers)
      players(player).userRef ! Information(gameBoard handCardsOf player, gameBoard.fieldCards.map(_._1))

  private def broadcast(appMessage: AppMessage): Unit = {
    for (player <- allPlayers)
      player.userRef ! appMessage
  }

  private def allPlayers: List[UserInfo] = teams(TEAM_1).players ++ teams(TEAM_2).players

  private implicit class RichContext(context: ActorContext){

    def toMatch(gameBoard: GameBoard, firstPlayer: String): Receive = {
      val receive = inMatch(gameBoard)
      context become receive
      turn(gameBoard, firstPlayer)
      receive
    }
  }

  implicit def userInfoToString(userInfo: UserInfo): String = userInfo.username

}


object SessionManager {

  case class LogOutAndExitFromGame(user: UserInfo)

  val TIMEOUT: Int = 40
  val TIMEOUT_TO_USER: Int = 30

  def apply(lobby: Lobby, gameKnowledge: GameKnowledge): Props = {
    import Lobby._
    val members = lobby.members.toList.splitAt(lobby.members.size/2)
    Props(classOf[SessionManager], gameKnowledge,
      Map((Team.TEAM_1, TeamPoints(members._1, 0)), (Team.TEAM_2, TeamPoints(members._2, 0))),
      lobbyToLobbyId(lobby),
      lobby.members.map(elem => (elem.username, elem)).toMap)
  }
}


private[this] object SessionManagerMessage {

  sealed trait SessionManagerMessage

  case class StartMatch(firstPlayer: Option[String]) extends SessionManagerMessage
  case class endMatchMessage(lastHandWinner: String) extends SessionManagerMessage
  case object Timeout extends SessionManagerMessage
  case object TimeoutId extends SessionManagerMessage

}