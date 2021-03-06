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
package com.waz.service

import com.waz.ZLog.{LogTag, verbose}
import com.waz.model.AccountId
import com.waz.service.AccountsService.LoggedOut
import com.waz.service.ZMessaging.accountTag
import com.waz.threading.SerialDispatchQueue
import com.waz.utils.events.EventContext

class AccountContext(accountId: AccountId, accounts: AccountsService) extends EventContext {

  implicit val logTag: LogTag = accountTag[AccountContext](accountId)

  private implicit val dispatcher = new SerialDispatchQueue(name = "AccountContext")

  accounts.accountState(accountId).on(dispatcher) {
    case LoggedOut =>
      verbose("Account context stopped")
      onContextStop()
    case _ =>
      verbose("Account context started")
      onContextStart()
  } (EventContext.Global)
}
