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
package com.waz.sync.handler

import com.waz.ZLog._
import com.waz.ZLog.ImplicitTag._
import com.waz.api.Message
import com.waz.api.impl.ErrorResponse
import com.waz.content.{ConversationStorageImpl, MessagesStorageImpl}
import com.waz.model.GenericContent.Cleared
import com.waz.model._
import com.waz.service.UserServiceImpl
import com.waz.service.conversation.ConversationsContentUpdaterImpl
import com.waz.sync.SyncResult
import com.waz.sync.otr.OtrSyncHandler
import org.threeten.bp.Instant

import scala.concurrent.Future

class ClearedSyncHandler(convs: ConversationStorageImpl, convsContent: ConversationsContentUpdaterImpl, users: UserServiceImpl, msgs: MessagesStorageImpl, convSync: ConversationsSyncHandler, otrSync: OtrSyncHandler) {
  import com.waz.threading.Threading.Implicits.Background

  // Returns actual timestamp to use for clear.
  // This is needed to take local (previously unsent) messages into account.
  // Clear may have been scheduled before some messages were sent,
  // in that case we want to use event and time of the last such message
  // (but we need to first send them to get that info).
  private[sync] def getActualClearInfo(convId: ConvId, time: Instant) =
    users.withSelfUserFuture { selfUserId =>
      msgs.findMessagesFrom(convId, time) map { ms =>
        verbose(s"getActualClearInfo, messages from clear time: $ms")

        val sentMessages = ms.takeWhile(m => m.time == time || m.userId == selfUserId && m.state == Message.Status.SENT)
        val t = sentMessages.lastOption.fold(time)(_.time)
        val archive = sentMessages.length == ms.length // archive only if there is no new or incoming message

        verbose(s"getActualClearInfo = ($t, $archive)")
        (t, archive)
      }
    }

  def postCleared(convId: ConvId, time: Instant): Future[SyncResult] = {
    verbose(s"postCleared($convId, $time)")

    def postTime(time: Instant, archive: Boolean) =
      convs.get(convId) flatMap {
        case None =>
          Future successful SyncResult(ErrorResponse.internalError(s"No conversation found for id: $convId"))
        case Some(conv) =>
          users.withSelfUserFuture { selfUserId =>
            val msg = GenericMessage(Uid(), Cleared(conv.remoteId, time))
            otrSync.postOtrMessage(ConvId(selfUserId.str), RConvId(selfUserId.str), msg) flatMap (_.fold(e => Future.successful(SyncResult(e)), { _ =>
              if (archive) convSync.postConversationState(conv.id, ConversationState(archived = Some(true), archiveTime = Some(time)))
              else Future.successful(SyncResult.Success)
            }))
          }
      }

    getActualClearInfo(convId, time) flatMap { case (t, archive) =>
      postTime(t, archive) flatMap {
        case SyncResult.Success =>
          convsContent.updateConversationCleared(convId, t) map { _ => SyncResult.Success }
        case res =>
          Future successful res
      }
    }
  }
}
