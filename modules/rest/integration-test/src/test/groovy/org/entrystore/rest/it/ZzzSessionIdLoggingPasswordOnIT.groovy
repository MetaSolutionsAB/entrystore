/*
 * Copyright (c) 2007-2026 MetaSolutions AB
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.entrystore.rest.it

import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.web.servlet.DispatcherServlet

import static java.net.HttpURLConnection.HTTP_OK

/**
 * No log line carries a session id with entrystore.auth.password=on, where Spring Security's own Basic converter is
 * used, and with request details logged and DispatcherServlet set to TRACE. The shared app runs with the whitelist,
 * whose converter {@link SessionIdLoggingIT} covers. The ITs run org.springframework.security at DEBUG.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSessionIdLoggingPasswordOnIT extends BaseSpec {

	static final String APPENDER_NAME = 'sessionIdLoggingPasswordOnIT'

	static final String DISPATCHER_SERVLET = 'org.springframework.web.servlet.DispatcherServlet'

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startOwnedApp(['--entrystore.auth.password=on', '--spring.mvc.log-request-details=true',
				'--logging.level.' + DISPATCHER_SERVLET + '=TRACE'])
		createCommonUserAccounts()
	}

	// Intentionally no cleanupSpec: the next lifecycle-owning IT's stopPreexistingAppIfRunning() closes our app.

	def "HTTP Basic credentials sent with another session's cookie should log no session id"() {
		given: 'every event captured at the root logger, and a session of another user'
		def appender = new ManagementLoggingIT.MemoryAppender(APPENDER_NAME)
		appender.start()
		loggerContext().getConfiguration().getRootLogger().addAppender(appender, null, null)
		loggerContext().updateLoggers()
		def login = EntryStoreClient.loginIsolated('user')

		when: 'a request carries the session cookie and the HTTP Basic credentials of admin'
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json',
				[Cookie       : 'auth_token=' + EntryStoreClient.cookieValueOf(login.authCookie),
				 Authorization: EntryStoreClient.basicAuth('admin', EntryStoreClient.creds['admin'].toString())])

		then: 'the request is served as admin'
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'admin'

		// Only logger names are compared, so that a failure report shows no captured message
		def sessionId = EntryStoreClient.sessionIdOf(login.authCookie)
		def loggers = appender.events.collect { it.loggerName } as Set
		def loggersWritingTheSessionId = appender.events
				.findAll { SessionIdLoggingIT.textOf(it).contains(sessionId) }
				.collect { it.loggerName } as Set

		and: 'the Basic authentication filter wrote its DEBUG events'
		loggers.any { it.startsWith('org.springframework.security.web.authentication.www.') }

		and: 'no event carries the session id'
		loggersWritingTheSessionId.isEmpty()

		cleanup:
		loggerContext().getConfiguration().getRootLogger().removeAppender(APPENDER_NAME)
		loggerContext().updateLoggers()
		appender?.stop()
	}

	def "a request with a session cookie should log no session id while request details are logged"() {
		given: 'every event captured at the root logger, and a session'
		def appender = new ManagementLoggingIT.MemoryAppender(APPENDER_NAME)
		appender.start()
		loggerContext().getConfiguration().getRootLogger().addAppender(appender, null, null)
		loggerContext().updateLoggers()
		def login = EntryStoreClient.loginIsolated('user')

		when: 'a request carries only the session cookie'
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json',
				[Cookie: 'auth_token=' + EntryStoreClient.cookieValueOf(login.authCookie)])

		then: 'the request is served as the user, and DispatcherServlet logs request details'
		conn.getResponseCode() == HTTP_OK
		JSON_PARSER.parseText(conn.inputStream.text)['user'] == 'user'
		appInstance.getBean(DispatcherServlet).isEnableLoggingRequestDetails()

		// Only logger names and levels are compared, so that a failure report shows no captured message
		def sessionId = EntryStoreClient.sessionIdOf(login.authCookie)
		def dispatcherServletLevels = appender.events
				.findAll { it.loggerName == DISPATCHER_SERVLET }
				.collect { it.level } as Set
		def loggersWritingTheSessionId = appender.events
				.findAll { SessionIdLoggingIT.textOf(it).contains(sessionId) }
				.collect { it.loggerName } as Set

		and: 'DispatcherServlet wrote DEBUG events but no TRACE events'
		dispatcherServletLevels.contains(Level.DEBUG)
		!dispatcherServletLevels.contains(Level.TRACE)

		and: 'no event carries the session id'
		loggersWritingTheSessionId.isEmpty()

		cleanup:
		loggerContext().getConfiguration().getRootLogger().removeAppender(APPENDER_NAME)
		loggerContext().updateLoggers()
		appender?.stop()
	}

	private static LoggerContext loggerContext() {
		return (LoggerContext) LogManager.getContext(false)
	}
}
