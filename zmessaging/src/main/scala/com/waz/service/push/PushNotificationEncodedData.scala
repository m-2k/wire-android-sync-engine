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
package com.waz.service.push

import android.content.Context
import com.waz.content.Database
import com.waz.db.Dao
import com.waz.db.Col._
import com.waz.model.Uid
import com.waz.service.push.PushNotificationEncodedData.PushNotificationEncodedDao
import com.waz.sync.client.PushNotificationEncoded
import com.waz.utils.TrimmingLruCache.Fixed
import com.waz.utils.wrappers.DBCursor
import com.waz.utils.{CachedStorage, CachedStorageImpl, TrimmingLruCache}
import org.json.JSONArray

trait PushNotificationEncodedStorage extends CachedStorage[Uid, PushNotificationEncoded]

class PushNotificationEncodedStorageImpl(context: Context, storage: Database)
  extends CachedStorageImpl[Uid, PushNotificationEncoded](new TrimmingLruCache(context, Fixed(100)),
                                              storage)(PushNotificationEncodedDao)
    with PushNotificationEncodedStorage


object PushNotificationEncodedData {

  implicit object PushNotificationEncodedDao extends Dao[PushNotificationEncoded, Uid] {
    val Id = id[Uid]('_receivedAt, "PRIMARY KEY").apply(_.id)
    val Data = text('events).apply(_.events.toString)
    val Transient = bool('transient)(_.transient)

    override val idCol = Id
    override val table = Table("PushNotificationEncoded", Id, Data)

    override def apply(implicit cursor: DBCursor): PushNotificationEncoded =
      PushNotificationEncoded(Id, new JSONArray(cursor.getString(1)), Transient)
  }
}
