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
package com.waz.log

import com.waz.ZLog.LogTag

import scala.concurrent.Future

class AndroidLogOutput extends LogOutput {

  override val id = AndroidLogOutput.id

  override def log(str: String, level: InternalLog.LogLevel, tag: LogTag): Unit = level match {
    case InternalLog.Error   => android.util.Log.e(tag, str)
    case InternalLog.Warn    => android.util.Log.w(tag, str)
    case InternalLog.Info    => android.util.Log.i(tag, str)
    case InternalLog.Debug   => android.util.Log.d(tag, str)
    case InternalLog.Verbose => android.util.Log.v(tag, str)
    case _ =>
  }

  override def log(str: String, cause: Throwable, level: InternalLog.LogLevel, tag: LogTag): Unit = level match {
    case InternalLog.Error => android.util.Log.e(tag, str, cause)
    case InternalLog.Warn  => android.util.Log.w(tag, str, cause)
    case _ =>
  }

  override def close(): Future[Unit] = Future.successful {}
  override def flush(): Future[Unit] = Future.successful {}
}

object AndroidLogOutput {
  val id = "android"
}