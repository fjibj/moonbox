/*-
 * <<
 * Moonbox
 * ==
 * Copyright (C) 2016 - 2018 EDP
 * ==
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 *      http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * >>
 */

package moonbox.grid.deploy.rest

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.Http.ServerBinding
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server._
import akka.stream.ActorMaterializer
import de.heikoseeberger.akkahttpjson4s.Json4sSupport.ShouldWritePretty
import moonbox.common.message._
import moonbox.common.{MbConf, MbLogging}
import moonbox.grid.config._
import moonbox.grid.deploy.MbService
import org.json4s.jackson.Serialization
import org.json4s.{CustomSerializer, DefaultFormats, JString, JInt}

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

class RestServer(host: String, port: Int, conf: MbConf, service: MbService,
	implicit val akkaSystem: ActorSystem) extends JsonSerializer with MbLogging {

	private val maxRetries: Int = conf.get(PORT_MAX_RETRIES.key, PORT_MAX_RETRIES.defaultValue.get)
	private var bindingFuture: Future[ServerBinding] = _
	private val loginManager = service.getLoginManager()
	implicit val materializer = ActorMaterializer()
	implicit val formats = DefaultFormats +   //add custom special serializer
		new CustomSerializer[java.sql.Date]( _ =>
			(	{ case JInt(s) => new java.sql.Date(s.longValue())},
				{ case x: java.sql.Date => JString(x.toString)})) +
		new CustomSerializer[java.math.BigDecimal]( _ =>
			(	{ case JString(s) => new java.math.BigDecimal(s) },
				{ case b: java.math.BigDecimal => JString(b.toString)}))

	implicit val serialization = Serialization
	implicit val shouldWritePretty = ShouldWritePretty.True


	val routes: Route = {
		path("login") {
			post {
				entity(as[LoginInbound]) { in =>
					complete {
						service.login(in.username, in.password).map {
							case Some(token) =>
								LoginOutbound(Some(token), None)
							case None =>
								LoginOutbound(None, Some(s"User '${in.username}' does not exist or password is incorrect."))
						}
					}
				}
			}
		} ~
		path("logout") {
			post {
				entity(as[LogoutInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								LogoutOutbound(error = Some("Token is incorrect or expired."))
							case true =>
								service.logout(in.token)
						}
					}
				}
			}
		} ~
		path("openSession") {
			post {
				entity(as[OpenSessionInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								LogoutOutbound(error = Some("Token is incorrect or expired."))
							case true =>
								service.openSession(in.token, in.database)
						}
					}
				}
			}
		} ~
		path("closeSession") {
			post {
				entity(as[CloseSessionInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								LogoutOutbound(error = Some("Token is incorrect or expired."))
							case true =>
								service.closeSession(in.token, in.sessionId)
						}
					}
				}
			}
		} ~
		path("query") {
			post {
				entity(as[QueryInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								LogoutOutbound(error = Some("Token is incorrect or expired."))
							case true =>
								service.jobQuery(in.token, in.sessionId, in.sqls)
						}
					}
				}
			}
		} ~
		path("submit") {
			post {
				entity(as[SubmitInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false => SubmitOutbound(error = Some("Token is incorrect or expired."))
							case true =>
								if (in.mode == "sync") {
									service.jobSubmitSync(in.token, in.sqls)
								} else {
									service.jobSubmitAsync(in.token, in.sqls)
								}
						}
					}
				}
			}
		} ~
		path("progress") {
			post {
				entity(as[ProgressInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								ProgressOutbound(jobId = in.jobId, error = Some("Token is incorrect or expired."))
							case true =>
								service.jobProgress(in.token, in.jobId)
						}
					}
				}
			}
		} ~
		path("result") {
			post {
				entity(as[ResultInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								ResultOutbound(jobId = in.jobId, error = Some("Token is incorrect or expired."))
							case true =>
								service.jobResult(in.token, in.jobId, in.offset, in.size)
						}
					}
				}
			}
		} ~
		path("cancel") {
			post {
				entity(as[CancelInbound]) { in =>
					complete {
						loginManager.isvalid(in.token) match {
							case false =>
								CancelOutbound(jobId = in.jobId, error = Some("Token is incorrect or expired."))
							case true =>
								service.jobCancel(in.token, in.jobId)
						}
					}
				}
			}
		}


	}

	def start(): Int = {
		require(port == 0 || (port >= 1024 && port < 65535),
			"rest port should be between 1024 (inclusive) and 65535 (exclusive), or 0 for a random free port.")
		logInfo("Starting RestServer ...")
		for (offset <- 0 to maxRetries) {
			val tryPort = if (port == 0) port else port + offset
			try {
				bindingFuture = Http().bindAndHandle(routes, host, tryPort)
				val serverBinding: ServerBinding = Await.result(bindingFuture, new FiniteDuration(10, SECONDS))
				logInfo(s"RestServer is listening on ${serverBinding.localAddress.toString}.")
				return serverBinding.localAddress.getPort
			} catch {
				case e: Exception =>
					if (offset >= maxRetries) {
						throw e
					}
					if (port == 0) {
						logWarning(s"RestServer could not bind on a random free pot. Attempting again.")
					} else {
						logWarning(s"RestServer could not bind on port $tryPort. " + "" +
							s"Attempting port ${tryPort + 1}")
					}
			}
		}
		throw new Exception(s"Failed to start RestServer on port $port")
	}

	def stop(): Unit = {
		if (bindingFuture != null) {
			bindingFuture.flatMap(_.unbind())
		}
	}

}
