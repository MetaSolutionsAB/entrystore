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

import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LoggerContext
import org.entrystore.rest.it.util.EntryStoreClient

import static java.net.HttpURLConnection.HTTP_OK

/**
 * HTTP Basic with entrystore.auth.password=on, where Spring Security's own Basic converter is used; the shared app runs
 * with the whitelist, whose converter {@link SessionIdLoggingIT} covers. The ITs run org.springframework.security at
 * DEBUG.
 */
// Zzz prefix sorts this class after all shared-app ITs under Failsafe's alphabetical runOrder.
class ZzzSessionIdLoggingPasswordOnIT extends BaseSpec {

	static final String APPENDER_NAME = 'sessionIdLoggingPasswordOnIT'

	def setupSpec() {
		stopPreexistingAppIfRunning()
		startOwnedApp(['--entrystore.auth.password=on'])
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

	private static LoggerContext loggerContext() {
		return (LoggerContext) LogManager.getContext(false)
	}
}
