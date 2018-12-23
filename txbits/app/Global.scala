// Flame.Exchange - An open source Bitcoin and crypto currency exchange
// Copyright (C) 2014-2015  Viktor Stanchev & Kirk Zathey
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public License
// along with this program.  If not, see <http://www.gnu.org/licenses/>.

import com.googlecode.jsonrpc4j.JsonRpcHttpClient
import controllers.IAPI.CryptoAddress
import java.net.{ PasswordAuthentication, URL }
import java.net.{ PasswordAuthentication, Authenticator, URL }
import play.api.db.DB
import play.api.i18n.{ MessagesApi, I18nSupport }
import play.api.mvc.Result
import play.api.Play.current
import scala.concurrent.duration._
import models._
import play.api._
import play.api.mvc._
import play.api.mvc.Results._
import play.api.libs.json.Json
import play.libs.Akka
import scala.collection.JavaConverters._
import scala.concurrent.Future
import service.txbitsUserService
import usertrust.{ UserTrustModel, UserTrustService }
import wallet.{ WalletModel, Wallet }
import anorm._

package object globals {
  val masterDB = "default"
  val masterDBWallet = "wallet"
  val masterDBTrusted = "trust"

  try {
    if (Play.current.configuration.getBoolean("meta.devdb").getOrElse(false)) {
      DB.withConnection(globals.masterDB)({ implicit c =>
        SQL"""
        begin;
      
      commit;
      """.execute()
      })
    }
  } catch {
    // XXX: any kind of error in the SQL above will cause this cryptic exception:
    // org.postgresql.util.PSQLException: Cannot change transaction read-only property in the middle of a transaction.
    case error: Throwable => Logger.error(error.toString)
  }

  val userModel = new UserModel(masterDB)
  val metaModel = new MetaModel(masterDB)
  val engineModel = new EngineModel(masterDB)
  val logModel = new LogModel(masterDB)

  val walletModel = new WalletModel(masterDBWallet)

  val userTrustModel = new UserTrustModel(masterDBTrusted)

  // create UserTrust actor
  val userTrustActor = current.configuration.getBoolean("usertrustservice.enabled").getOrElse(false) match {
    case true => Some(Akka.system.actorOf(UserTrustService.props(userTrustModel)))
    case false => None
  }

  // set up rpc authenticator for wallets
  val rpcAuth = DefaultAuthenticator.getInstance()

  // create wallet actors from config
  //TODO: separate wallet from frontend
  val currencies = List(
    "bitcoin" -> Wallet.CryptoCurrency.BTC,
    "litecoin" -> Wallet.CryptoCurrency.LTC,
    "piratecash" -> Wallet.CryptoCurrency.PIRATE,
    "rebooted" -> Wallet.CryptoCurrency.BOOT,
    "eazycoin" -> Wallet.CryptoCurrency.EAZY)

  val enabledCurrencies = currencies.filter(c =>
    Play.current.configuration.getBoolean("wallet.%s.enabled".format(c._1)).getOrElse(false))

  val wallets = for {
    (currencyName, currency) <- enabledCurrencies
    nodeId <- Play.current.configuration.getIntList("wallet.%s.node.ids".format(currencyName)).get.asScala
  } yield {
    val result = for {
      rpcUrlString <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.url".format(currencyName, nodeId))
      rpcUser <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.user".format(currencyName, nodeId))
      rpcPassword <- Play.current.configuration.getString("wallet.%s.node.%s.rpc.password".format(currencyName, nodeId))
      checkDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.checkDelay".format(currencyName, nodeId))
      checkInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.checkInterval".format(currencyName, nodeId))
      addressDelay <- Play.current.configuration.getInt("wallet.%s.node.%s.addressDelay".format(currencyName, nodeId))
      addressInterval <- Play.current.configuration.getInt("wallet.%s.node.%s.addressInterval".format(currencyName, nodeId))
      addressPool <- Play.current.configuration.getInt("wallet.%s.node.%s.addressPool".format(currencyName, nodeId))
    } yield {
      val backupPath = Play.current.configuration.getString("wallet.%s.node.%s.backupPath".format(currencyName, nodeId)) match {
        case Some(path) if path.startsWith("/") => Some(path)
        case Some(_) =>
          Logger.warn("Backup path specified, but is not absolute (starting with /). Backups are disabled."); None
        case None => None
      }
      val coldAddress = Play.current.configuration.getString("wallet.%s.node.%s.coldAddress".format(currencyName, nodeId)) match {
        case Some(address) if CryptoAddress.isValid(address, currency.toString, Play.current.configuration.getBoolean("fakeexchange").get) => Some(address)
        case Some(_) =>
          Logger.warn("Invalid cold storage address for %s wallet. Cold storage disabled.".format(currency)); None
        case None => None
      }
      val refillEmail = Play.current.configuration.getString("wallet.%s.node.%s.refillEmail".format(currencyName, nodeId)) match {
        case Some(email) if email.contains("@") => Some(email)
        case Some(_) =>
          Logger.warn("Invalid email address for %s wallet. Refill notifications disabled.".format(currency)); None
        case None => None
      }
      val maxTxFee = Play.current.configuration.getString("wallet.%s.node.%s.maxTxFee".format(currencyName, nodeId)) match {
        case Some(fee) =>
          try {
            val f = BigDecimal(fee)
            if (f > 0) {
              f
            } else {
              Logger.warn("Invalid max tx fee for %s wallet.".format(currency))
              BigDecimal(0)
            }
          } catch {
            case _: Throwable =>
              Logger.warn("Invalid max tx fee for %s wallet.".format(currency))
              BigDecimal(0)
          }
        case _ => BigDecimal(0)
      }

      val rpcUrl = new URL(rpcUrlString)
      rpcAuth.register(rpcUrl, new PasswordAuthentication(rpcUser, rpcPassword.toCharArray))
      val params = Wallet.WalletParams(checkDelay.seconds, checkInterval.seconds, addressDelay.seconds, addressInterval.seconds, addressPool, backupPath, coldAddress, refillEmail, maxTxFee)
      Akka.system.actorOf(Wallet.props(new JsonRpcHttpClient(rpcUrl), currency, nodeId, params, walletModel))
    }

    if (result.isEmpty) {
      Logger.warn("One or more required parameters not provided for %s wallet. %s wallet disabled. Required parameters: %s".format(currency, currency, "url, user, password, checkDelay, checkInterval, addressDelay, addressInterval, addressPool"))
    }
    ((currency, nodeId), result)
  }
}

object Global extends GlobalSettings {

  override def onStart(app: Application) {
    Logger.info("Application has started")
    // This is a somewhat hacky way to exit after statup so that we can apply database changes without stating the app
    if (Play.current.configuration.getBoolean("meta.exitimmediately").getOrElse(false)) {
      Logger.warn("Exiting because of meta.exitimmediately config set to true.")
      System.exit(0)
    }
    txbitsUserService.onStart()
  }

  override def onStop(app: Application) {
    Logger.info("Application shutdown...")
    txbitsUserService.onStop()
  }
}
