/*
 * Wire
 * Copyright (C) 2016 Wire Swiss GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.waz.model

import java.util.Date

import android.util.Base64
import com.waz.ZLog.ImplicitTag._
import com.waz.ZLog._
import com.waz.model.ConversationEvent.ConversationEventDecoder
import com.waz.model.Event.EventDecoder
import com.waz.model.UserData.ConnectionStatus
import com.waz.model.otr.{Client, ClientId}
import com.waz.sync.client.ConversationsClient.ConversationResponse
import com.waz.sync.client.OtrClient
import com.waz.utils.JsonDecoder._
import com.waz.utils.{JsonDecoder, JsonEncoder, _}
import com.waz.znet.ContentEncoder
import com.waz.znet.ContentEncoder.JsonContentEncoder
import org.json.{JSONArray, JSONException, JSONObject}
import org.threeten.bp.Instant

import scala.collection.mutable.ArrayBuffer
import scala.util.Try

sealed trait Event {
  import Event._

  //FIXME do we still need this separation?
  var localTime: Date = UnknownDateTime

  def withCurrentLocalTime(): this.type = {
    localTime = new Date()
    this
  }

  def withLocalTime(time: Date): this.type = {
    localTime = time
    this
  }

  def hasLocalTime: Boolean = localTime != UnknownDateTime

  def maybeLocalTime: Option[Instant] = if (localTime == UnknownDateTime) None else Some(localTime.instant)
}

sealed trait UserEvent extends Event
sealed trait OtrClientEvent extends UserEvent

sealed trait RConvEvent extends Event {
  val convId: RConvId
}
object RConvEvent extends (Event => RConvId) {
  def apply(ev: Event): RConvId = ev match {
    case ev: RConvEvent => ev.convId
    case _              => RConvId.Empty
  }
}
case class UserUpdateEvent(user: UserInfo, removeIdentity: Boolean = false) extends UserEvent
case class UserPropertiesSetEvent(key: String, value: String) extends UserEvent // value is always json string, so maybe we should parse it already (or maybe not)
case class UserConnectionEvent(convId: RConvId, from: UserId, to: UserId, message: Option[String], status: ConnectionStatus, lastUpdated: Date, fromUserName: Option[String] = None) extends UserEvent with RConvEvent
case class UserDeleteEvent(user: UserId) extends UserEvent
case class OtrClientAddEvent(client: Client) extends OtrClientEvent
case class OtrClientRemoveEvent(client: ClientId) extends OtrClientEvent

case class ContactJoinEvent(user: UserId, name: String) extends Event

case class PushTokenRemoveEvent(token: PushToken, senderId: String, client: Option[String]) extends Event

sealed trait ConversationEvent extends RConvEvent {
  val time: Date
  val from: UserId
}

// events that affect conversation state
sealed trait ConversationStateEvent extends ConversationEvent

// events that add or modify some message
sealed trait MessageEvent extends ConversationEvent

case class UnknownEvent(json: JSONObject) extends Event
case class UnknownConvEvent(json: JSONObject) extends ConversationEvent {
  override val convId: RConvId = RConvId()
  override val from: UserId = UserId()
  override val time: Date = new Date
}

case class CreateConversationEvent(convId: RConvId, time: Date, from: UserId, data: ConversationResponse) extends ConversationStateEvent

case class RenameConversationEvent(convId: RConvId, time: Date, from: UserId, name: String) extends MessageEvent with ConversationStateEvent

case class GenericMessageEvent(convId: RConvId, time: Date, from: UserId, content: GenericMessage) extends MessageEvent

case class CallMessageEvent(convId: RConvId, time: Date, from: UserId, sender: ClientId, content: String) extends MessageEvent

sealed trait OtrError
case object Duplicate extends OtrError
case class DecryptionError(msg: String, from: UserId, sender: ClientId) extends OtrError
case class IdentityChangedError(from: UserId, sender: ClientId) extends OtrError
case class UnknownOtrErrorEvent(json: JSONObject) extends OtrError

case class OtrErrorEvent(convId: RConvId, time: Date, from: UserId, error: OtrError) extends MessageEvent

case class GenericAssetEvent(convId: RConvId, time: Date, from: UserId, content: GenericMessage, dataId: RAssetId, data: Option[Array[Byte]]) extends MessageEvent

case class TypingEvent(convId: RConvId, time: Date, from: UserId, isTyping: Boolean) extends ConversationEvent

case class MemberJoinEvent(convId: RConvId, time: Date, from: UserId, userIds: Seq[UserId], firstEvent: Boolean = false) extends MessageEvent with ConversationStateEvent with ConversationEvent
case class MemberLeaveEvent(convId: RConvId, time: Date, from: UserId, userIds: Seq[UserId]) extends MessageEvent with ConversationStateEvent
case class MemberUpdateEvent(convId: RConvId, time: Date, from: UserId, state: ConversationState) extends ConversationStateEvent

case class ConnectRequestEvent(convId: RConvId, time: Date, from: UserId, message: String, recipient: UserId, name: String, email: Option[String]) extends MessageEvent with ConversationStateEvent

sealed trait OtrEvent extends ConversationEvent {
  val sender: ClientId
  val recipient: ClientId
  val ciphertext: Array[Byte]
}
case class OtrMessageEvent(convId: RConvId, time: Date, from: UserId, sender: ClientId, recipient: ClientId, ciphertext: Array[Byte], externalData: Option[Array[Byte]] = None) extends OtrEvent

case class OtrAssetEvent(convId: RConvId, time: Date, from: UserId, sender: ClientId, recipient: ClientId, dataId: RAssetId, ciphertext: Array[Byte], imageData: Option[Array[Byte]]) extends OtrEvent

case class ConversationState(archived: Option[Boolean] = None, archiveTime: Option[Instant] = None, muted: Option[Boolean] = None, muteTime: Option[Instant] = None)

object ConversationState {

  private def encode(state: ConversationState, o: JSONObject) = {
    state.archived foreach { o.put("otr_archived", _) }
    state.archiveTime foreach { time =>
      o.put("otr_archived_ref", JsonEncoder.encodeISOInstant(time))
    }
    state.muted.foreach(o.put("otr_muted", _))
    state.muteTime foreach { time =>
      o.put("otr_muted_ref", JsonEncoder.encodeISOInstant(time))
    }
  }

  implicit lazy val Encoder: JsonEncoder[ConversationState] = new JsonEncoder[ConversationState] {
    override def apply(state: ConversationState): JSONObject = JsonEncoder { o => encode(state, o) }
  }

  implicit lazy val Decoder: JsonDecoder[ConversationState] = new JsonDecoder[ConversationState] {
    import com.waz.utils.JsonDecoder._

    override def apply(implicit js: JSONObject): ConversationState = {
      val archiveTime = decodeOptISOInstant('otr_archived_ref)
      val archived = archiveTime.map( _ => decodeBool('otr_archived))

      val (muted, muteTime) = (decodeOptISOInstant('otr_muted_ref), decodeOptISOInstant('muted_time)) match {
        case (Some(t), Some(t1)) if t1.isAfter(t) => (decodeOptBoolean('muted), Some(t1))
        case (t @ Some(_), _)                     => (decodeOptBoolean('otr_muted), t)
        case (_, t @ Some(_))                     => (decodeOptBoolean('muted), t)
        case _                                    => (None, None)
      }

      ConversationState(archived, archiveTime, muted, muteTime)
    }
  }

  implicit val StateContentEncoder: ContentEncoder[ConversationState] =
    JsonContentEncoder map { (state: ConversationState) => JsonEncoder { encode(state, _) } }
}

object Event {

  val UnknownDateTime: Date = MessageData.UnknownInstant.javaDate

  implicit object EventDecoder extends JsonDecoder[Event] {

    import com.waz.utils.JsonDecoder._

    def connectionEvent(implicit js: JSONObject, name: Option[String]) = UserConnectionEvent('conversation, 'from, 'to, 'message, ConnectionStatus('status), 'last_update, fromUserName = name)

    def contactJoinEvent(implicit js: JSONObject) = ContactJoinEvent('id, 'name)

    def gcmTokenRemoveEvent(implicit js: JSONObject) = PushTokenRemoveEvent(token = 'token, senderId = 'app, client = 'client)

    override def apply(implicit js: JSONObject): Event = LoggedTry {

      lazy val data = if (js.has("data") && !js.isNull("data")) Try(js.getJSONObject("data")).toOption else None

      decodeString('type) match {
        case tpe if tpe.startsWith("conversation") => ConversationEventDecoder(js)
        case tpe if tpe.startsWith("team")         => TeamEvent.TeamEventDecoder(js)
        case "user.update" => UserUpdateEvent(JsonDecoder[UserInfo]('user))
        case "user.identity-remove" => UserUpdateEvent(JsonDecoder[UserInfo]('user), true)
        case "user.connection" => connectionEvent(js.getJSONObject("connection"), JsonDecoder.opt('user, _.getJSONObject("user")) flatMap (JsonDecoder.decodeOptString('name)(_)))
        case "user.contact-join" => contactJoinEvent(js.getJSONObject("user"))
        case "user.push-remove" => gcmTokenRemoveEvent(js.getJSONObject("token"))
        case "user.properties-set" => UserPropertiesSetEvent('key, 'value)
        case "user.delete" => UserDeleteEvent(user = 'id)
        case "user.client-add" => OtrClientAddEvent(OtrClient.ClientsResponse.client(js.getJSONObject("client")))
        case "user.client-remove" => OtrClientRemoveEvent(decodeId[ClientId]('id)(js.getJSONObject("client"), implicitly))
        case _ =>
          error(s"unhandled event: $js")
          UnknownEvent(js)
      }
    } .getOrElse(UnknownEvent(js))
  }
}

object UserConnectionEvent {
  implicit lazy val Decoder: JsonDecoder[UserConnectionEvent] = new JsonDecoder[UserConnectionEvent] {
    override def apply(implicit js: JSONObject): UserConnectionEvent = EventDecoder.connectionEvent(js, name = None)
  }
}

object ConversationEvent {

  import OtrErrorEvent._

  def unapply(e: ConversationEvent): Option[(RConvId, Date, UserId)] =
    Some((e.convId, e.time, e.from))

  implicit lazy val ConversationEventDecoder: JsonDecoder[ConversationEvent] = new JsonDecoder[ConversationEvent] {
    private def decodeBytes(str: String) = Base64.decode(str, Base64.NO_WRAP)

    def otrMessageEvent(convId: RConvId, time: Date, from: UserId)(implicit data: JSONObject) =
      OtrMessageEvent(convId, time, from, ClientId('sender), ClientId('recipient), decodeBytes('text), decodeOptString('data).map(decodeBytes))

    def otrAssetEvent(convId: RConvId, time: Date, from: UserId)(implicit data: JSONObject) =
      OtrAssetEvent(convId, time, from, ClientId('sender), ClientId('recipient), RAssetId('id), decodeBytes('key), decodeOptString('data).map(decodeBytes))

    def genericAssetEvent(convId: RConvId, time: Date, from: UserId, content: GenericMessage,
                          dataId: RAssetId)(implicit js: JSONObject) =
      GenericAssetEvent(convId, time, from, content, dataId, decodeOptString('data).map(decodeBytes))

    def otrErrorEvent(convId: RConvId, time: Date, from: UserId)(implicit js: JSONObject) =
      OtrErrorEvent(convId, time, from, decodeOtrError('error))

    override def apply(implicit js: JSONObject): ConversationEvent = LoggedTry {

      lazy val data = if (js.has("data") && !js.isNull("data")) Try(js.getJSONObject("data")).toOption else None

      decodeString('type) match {
        case "conversation.create" => CreateConversationEvent('conversation, 'time, 'from, JsonDecoder[ConversationResponse]('data))
        case "conversation.rename" => RenameConversationEvent('conversation, 'time, 'from, decodeString('name)(data.get))
        case "conversation.member-join" =>
          MemberJoinEvent('conversation, 'time, 'from, decodeUserIdSeq('user_ids)(data.get), decodeString('id).startsWith("1."))
        case "conversation.member-leave" => MemberLeaveEvent('conversation, 'time, 'from, decodeUserIdSeq('user_ids)(data.get))
        case "conversation.member-update" => MemberUpdateEvent('conversation, 'time, 'from, ConversationState.Decoder(data.get))
        case "conversation.connect-request" =>
          ConnectRequestEvent('conversation, 'time, 'from, decodeString('message)(data.get),
            decodeUserId('recipient)(data.get), decodeString('name)(data.get), decodeOptString('email)(data.get))
        case "conversation.typing" =>
          TypingEvent('conversation, 'time, 'from, isTyping = data.fold(false)(data => decodeString('status)(data) == "started"))
        case "conversation.otr-message-add" => otrMessageEvent('conversation, 'time, 'from)(data.get)
          //TODO remove after v2 transition period is over - no more clients should be sending v2
        case "conversation.otr-asset-add" => otrAssetEvent('conversation, 'time, 'from)(data.get)
        case "conversation.generic-message" => GenericMessageEvent('convId, 'time, 'from, 'content)
        case "conversation.generic-asset" => genericAssetEvent('convId, 'time, 'from, 'content, 'dataId)
        case "conversation.otr-error" => otrErrorEvent('convId, 'time, 'from)
        case "conversation.call-message" => CallMessageEvent('convId, 'time, 'from, 'sender, 'content)
        case _ =>
          error(s"unhandled event: $js")
          UnknownConvEvent(js)
      }
    } .getOrElse {
      error(s"unhandled event: $js")
      UnknownConvEvent(js)
    }
  }
}

object OtrErrorEvent {

  def decodeOtrError(s: Symbol)(implicit js: JSONObject): OtrError =
    OtrErrorDecoder(js.getJSONObject(s.name))

  implicit lazy val OtrErrorDecoder: JsonDecoder[OtrError] = new JsonDecoder[OtrError] {
    override def apply(implicit js: JSONObject): OtrError = LoggedTry {
      decodeString('type) match {
        case "otr-error.decryption-error" => DecryptionError('msg, 'from, 'sender)
        case "otr-error.identity-changed-error" => IdentityChangedError('from, 'sender)
        case "otr-error.duplicate" => Duplicate
        case _ =>
          error(s"unhandled event: $js")
          UnknownOtrErrorEvent(js)
      }
    }.getOrElse {
      error(s"unhandled event: $js")
      UnknownOtrErrorEvent(js)
    }
  }
}

object MessageEvent {
  import JSONHelpers._

  implicit lazy val MessageEventEncoder: JsonEncoder[MessageEvent] = new JsonEncoder[MessageEvent] {

    private def setFields(json: JSONObject, convId: RConvId, time: Date, from: UserId, eventType: String) =
      json
        .put("convId", convId.str)
        .put("time", JsonEncoder.encodeDate(time))
        .put("from", from.str)
        .put("type", eventType)
        .setType(eventType)

    override def apply(event: MessageEvent): JSONObject = JsonEncoder { json =>
      event match {
        case GenericMessageEvent(convId, time, from, content) =>
          setFields(json, convId, time, from, "conversation.generic-message")
            .put("content", Base64.encodeToString(GenericMessage.toByteArray(content), Base64.NO_WRAP))
        case GenericAssetEvent(convId, time, from, content, dataId, data) =>
          setFields(json, convId, time, from, "conversation.generic-asset")
            .put("dataId", dataId.str)
            .put("data", data match {
              case None => null
              case Some(d) => Base64.encodeToString(d, Base64.NO_WRAP)
            })
            .put("content", Base64.encodeToString(GenericMessage.toByteArray(content), Base64.NO_WRAP))
        case OtrErrorEvent(convId, time, from, error) =>
          setFields(json, convId, time, from, "conversation.otr-error")
            .put("error", OtrError.OtrErrorEncoder(error))
        case CallMessageEvent(convId, time, from, sender, content) =>
          setFields(json, convId, time, from, "conversation.call-message")
            .put("sender", sender.str)
            .put("content", content)
        case e => throw new JSONException(s"Encoder for event $e not implemented")
      }
    }
  }
}

object OtrError {
  import JSONHelpers._

  implicit lazy val OtrErrorEncoder: JsonEncoder[OtrError] = new JsonEncoder[OtrError] {
    override def apply(error: OtrError): JSONObject = JsonEncoder { json =>
      error match {
        case DecryptionError(msg, from, sender) =>
          json
            .put("msg", msg)
            .put("from", from.str)
            .put("sender", sender.str)
            .setType("otr-error.decryption-error")
        case IdentityChangedError(from, sender) =>
          json
            .put("from", from.str)
            .put("sender", sender.str)
            .setType("otr-error.identity-changed-error")
        case Duplicate => json.setType("otr-error.duplicate")
        case e => throw new JSONException(s"Encoder for event $e not implemented")
      }
    }
  }
}

object JSONHelpers {
  implicit class RichJSONArray(js: JSONArray) {
    def foldLeft[B](z: B)(op: (B, JSONObject) => B): B = {
      var result = z
      for (i <- 0 to js.length()) {
        result = op(result, js.getJSONObject(i))
      }
      result
    }

    def JSONArrayToVector: Seq[JSONObject] =
      js.foldLeft(new ArrayBuffer[JSONObject])((buf, obj) => buf += obj)
  }

  def vectorToJSONArray(seq: Seq[JSONObject]): JSONArray =
    seq.foldLeft(new JSONArray())((array, elem) => array.put(elem))

  implicit class RichJSONObject(js: JSONObject) {
    def setType(eventType: String): JSONObject = js.put("type", eventType)
  }
}

sealed trait TeamEvent extends Event {
  val teamId: TeamId
}

object TeamEvent {

  /**
    * See: https://github.com/wireapp/architecture/blob/master/teams/backend.md
    */

  case class Create(teamId: TeamId) extends TeamEvent
  case class Delete(teamId: TeamId) extends TeamEvent
  case class Update(teamId: TeamId, name: Option[String], icon: Option[RAssetId], iconKey: Option[AESKey]) extends TeamEvent

  sealed trait MemberEvent extends TeamEvent {
    val userId: UserId
  }
  case class MemberJoin(teamId: TeamId, userId: UserId) extends MemberEvent
  case class MemberLeave(teamId: TeamId, userId: UserId) extends MemberEvent
  case class MemberUpdate(teamId: TeamId, userId: UserId) extends MemberEvent

  sealed trait ConversationEvent extends TeamEvent {
    val convId: RConvId
  }

  case class ConversationCreate(teamId: TeamId, convId: RConvId) extends ConversationEvent
  case class ConversationDelete(teamId: TeamId, convId: RConvId) extends ConversationEvent

  case class UnknownTeamEvent(js: JSONObject) extends TeamEvent { override val teamId = TeamId.Empty }

  implicit lazy val TeamEventDecoder: JsonDecoder[TeamEvent] = new JsonDecoder[TeamEvent] {

    override def apply(implicit js: JSONObject): TeamEvent =
      decodeString('type) match {
        case "team.create"              => Create('team)
        case "team.delete"              => Delete('team)
        case "team.update"              => Update('team, decodeOptString('name)('data), decodeOptString('icon)('data).map(RAssetId), decodeOptString('icon_key)('data).map(AESKey))
        case "team.member-join"         => MemberJoin ('team, UserId(decodeString('user)('data)))
        case "team.member-leave"        => MemberLeave('team, UserId(decodeString('user)('data)))
        case "team.member-update"       => MemberUpdate('team, UserId(decodeString('user)('data)))
        case "team.conversation-create" => ConversationCreate('team, RConvId(decodeString('conv)('data)))
        case "team.conversation-delete" => ConversationDelete('team, RConvId(decodeString('conv)('data)))
        case _ =>
          error(s"Unhandled event: $js")
          UnknownTeamEvent(js)
    }
  }
}

object OtrClientRemoveEvent {
  import JSONHelpers._
  implicit lazy val Encoder: JsonEncoder[OtrClientRemoveEvent] =
    new JsonEncoder[OtrClientRemoveEvent] {
      override def apply(error: OtrClientRemoveEvent): JSONObject = JsonEncoder { json =>
        json.setType("user.client-remove")
        json.put("client", new JSONObject().put("id", error.client.toString))
      }
    }
}
