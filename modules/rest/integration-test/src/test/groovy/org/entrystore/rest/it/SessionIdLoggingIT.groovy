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

import groovy.json.JsonOutput
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpSession
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.layout.PatternLayout
import org.entrystore.rest.it.util.EntryStoreClient
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.AnonymousAuthenticationFilter
import org.springframework.security.web.authentication.WebAuthenticationDetails

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

/**
 * No log line carries a session id, also with every logger set to TRACE through /management/loggers, except the IT's
 * own HTTP client.
 */
class SessionIdLoggingIT extends BaseSpec {

	// ROOT first, so that it is restored first and a failing restore of another logger cannot leave it at TRACE
	static final Map<String, String> LEVELS = [
			'ROOT'                                       : 'TRACE',
			// levels of their own in the IT configuration, which ROOT does not override
			'org.springframework.security'               : 'TRACE',
			'org.eclipse.jetty'                          : 'TRACE',
			'org.entrystore'                             : 'TRACE',
			// the IT's own HTTP client, which logs the headers it sends and receives
			'sun.net.www.protocol.http.HttpURLConnection': 'INFO'
	]

	static final String FORWARDED_HEADER_FILTER = 'org.springframework.web.filter.ForwardedHeaderFilter'

	static final String APPENDER_NAME = 'sessionIdLoggingIT'

	// the message and the exception with its causes and suppressed exceptions, as an appender writes them
	private static final PatternLayout TEXT_LAYOUT = PatternLayout.newBuilder().withPattern('%m%n%ex').build()

	// entrystore.auth.session.reload-interval of the ITs, in milliseconds
	private static final long RELOAD_INTERVAL_MILLIS = 10_000

	def "a login, its renewal, reload, listing, ending and logout should log no session id at TRACE"() {
		given: 'TRACE for every logger but the IT client, and every event captured at the root logger'
		def previousLevels = LEVELS.keySet().collectEntries { [(it): previousLevelOf(it)] }
		LEVELS.each { name, level -> setLevel(name, level) }
		def appender = new ManagementLoggingIT.MemoryAppender(APPENDER_NAME)
		appender.start()
		loggerContext().getConfiguration().getRootLogger().addAppender(appender, null, null)
		loggerContext().updateLoggers()
		def authCookies = []

		when: 'a session logs in, and logs in again with its cookie, which changes its id'
		def first = EntryStoreClient.loginIsolated('user')
		authCookies << first.authCookie
		def relogin = EntryStoreClient.postRequest('/auth/cookie',
				createFormBody([auth_username: 'user', auth_password: EntryStoreClient.creds['user'].toString()]),
				'', 'application/x-www-form-urlencoded', [Cookie: cookieOf(first.authCookie)])
		assert relogin.getResponseCode() == HTTP_OK
		// the response may also expire the cookie on other paths; take the one it issues
		def renewed = EntryStoreClient.findSetCookies(relogin, 'auth_token')
				.find { EntryStoreClient.parseSetCookieAttributes(it)['max-age'] != '0' }
		authCookies << renewed

		and: 'its user is reloaded, its sessions are listed, and it carries the HTTP Basic credentials of another user'
		sleep(RELOAD_INTERVAL_MILLIS + 200)
		def reloaded = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookieOf(renewed)])
		def handle = EntryStoreClient.handleOf(cookieOf(renewed))
		def withOtherCredentials = EntryStoreClient.getRequest('/auth/user', '', 'application/json',
				[Cookie: cookieOf(renewed), Authorization: EntryStoreClient.basicAuth('admin',
						EntryStoreClient.creds['admin'].toString())])

		and: 'another session of the user ends it and logs out'
		def other = EntryStoreClient.loginIsolated('user')
		authCookies << other.authCookie
		def ending = EntryStoreClient.deleteRequest('/auth/tokens', JsonOutput.toJson([token: handle]), '',
				'application/json', EntryStoreClient.csrfHeaders(other.authCookie, other.csrf))
		def afterEnding = EntryStoreClient.getRequest('/auth/user', '', 'application/json', [Cookie: cookieOf(renewed)])
		def logout = EntryStoreClient.postRequest('/auth/logout', '', '', 'application/json',
				EntryStoreClient.csrfHeaders(other.authCookie, other.csrf))

		then: 'each step had its effect'
		userOf(reloaded) == 'user'
		userOf(withOtherCredentials) == 'admin'
		ending.getResponseCode() == HTTP_NO_CONTENT
		afterEnding.getResponseCode() == HTTP_UNAUTHORIZED
		logout.getResponseCode() == HTTP_NO_CONTENT

		// Only logger names are compared, so that a failure report shows no captured message
		def sessionIds = authCookies.collect { EntryStoreClient.sessionIdOf(it) }
		def loggers = appender.events.collect { it.loggerName } as Set
		def loggersAtTrace = appender.events.findAll { it.level == Level.TRACE }.collect { it.loggerName } as Set
		def jettyLoggersBelowInfo = loggersBelow(appender.events, 'org.eclipse.jetty.', Level.INFO)
		def jettySessionLoggersBelowError = loggersBelow(appender.events, 'org.eclipse.jetty.session.', Level.ERROR)
		def loggersWritingSessionIds = appender.events
				.findAll { event -> sessionIds.any { textOf(event).contains(it) } }
				.collect { it.loggerName } as Set

		and: 'Spring Security wrote TRACE events, but the session registry wrote none'
		loggersAtTrace.any { it.startsWith('org.springframework.security.') }
		loggers.findAll { it.startsWith('org.springframework.security.core.session.') }.isEmpty()

		and: 'nothing below INFO from Jetty, below ERROR from its session handling, below DEBUG from DispatcherServlet'
		jettyLoggersBelowInfo.isEmpty()
		jettySessionLoggersBelowError.isEmpty()
		loggersBelow(appender.events, 'org.springframework.web.servlet.DispatcherServlet', Level.DEBUG).isEmpty()

		and: 'no event carries a session id'
		loggersWritingSessionIds.isEmpty()

		cleanup:
		loggerContext().getConfiguration().getRootLogger().removeAppender(APPENDER_NAME)
		loggerContext().updateLoggers()
		appender?.stop()
		previousLevels?.each { name, level -> setLevel(name as String, level as String) }
	}

	def "a request with an unparseable forwarded port should log no session id at DEBUG"() {
		given: 'DEBUG for the forwarded-header filter, every event captured at the root logger, and a session'
		def previousLevel = previousLevelOf(FORWARDED_HEADER_FILTER)
		setLevel(FORWARDED_HEADER_FILTER, 'DEBUG')
		def appender = new ManagementLoggingIT.MemoryAppender(APPENDER_NAME)
		appender.start()
		loggerContext().getConfiguration().getRootLogger().addAppender(appender, null, null)
		loggerContext().updateLoggers()
		def login = EntryStoreClient.loginIsolated('user')

		when: 'a request with the session cookie carries a forwarded port that is not a number'
		def conn = EntryStoreClient.getRequest('/auth/user', '', 'application/json',
				[Cookie: cookieOf(login.authCookie), 'X-Forwarded-Port': 'not-a-port'])

		then: 'the request is rejected'
		conn.getResponseCode() == HTTP_BAD_REQUEST

		// Only logger names are compared, so that a failure report shows no captured message
		def sessionId = EntryStoreClient.sessionIdOf(login.authCookie)
		def loggersWritingTheSessionId = appender.events
				.findAll { textOf(it).contains(sessionId) }
				.collect { it.loggerName } as Set

		and: 'the filter wrote nothing below INFO, and no event carries the session id'
		loggersBelow(appender.events, FORWARDED_HEADER_FILTER, Level.INFO).isEmpty()
		loggersWritingTheSessionId.isEmpty()

		cleanup:
		loggerContext().getConfiguration().getRootLogger().removeAppender(APPENDER_NAME)
		loggerContext().updateLoggers()
		appender?.stop()
		setLevel(FORWARDED_HEADER_FILTER, previousLevel)
	}

	def "an anonymous authentication should record the client address but no session id"() {
		given: 'the anonymous filter of the security filter chain, and a request that carries a session'
		def anonymousFilter = appInstance.getBean(SecurityFilterChain).filters
				.find { it instanceof AnonymousAuthenticationFilter } as AnonymousAuthenticationFilter
		assert anonymousFilter != null
		def session = [getId: { 'anonymous-session-id' }] as HttpSession
		def request = [getRemoteAddr: { '192.0.2.1' }, getSession: { create -> session }] as HttpServletRequest
		def details = null
		def readingFilter = { req, res ->
			details = SecurityContextHolder.getContext().getAuthentication().getDetails()
		} as FilterChain

		when: 'the filter runs, and the next filter reads the authentication'
		anonymousFilter.doFilter(request, null, readingFilter)

		then:
		details instanceof WebAuthenticationDetails
		details.remoteAddress == '192.0.2.1'
		details.sessionId == null

		cleanup:
		SecurityContextHolder.clearContext()
	}

	private static LoggerContext loggerContext() {
		return (LoggerContext) LogManager.getContext(false)
	}

	/**
	 * @return the level to restore a logger to: ROOT's effective level, which is never null, or the configured level
	 * of another logger, null if it inherits its level or does not exist yet
	 */
	private static String previousLevelOf(String logger) {
		if (logger == 'ROOT') {
			// only the listing names the root logger ROOT; GET /management/loggers/ROOT answers 404 with Log4j
			def listing = EntryStoreClient.getRequest('/management/loggers')
			assert listing.getResponseCode() == HTTP_OK
			return JSON_PARSER.parseText(listing.inputStream.text)['loggers']['ROOT']['effectiveLevel']
		}
		def conn = EntryStoreClient.getRequest('/management/loggers/' + logger)
		if (conn.getResponseCode() == HTTP_NOT_FOUND) {
			return null
		}
		assert conn.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(conn.inputStream.text)['configuredLevel']
	}

	/** Sets the level of a logger; null resets it to the one it inherits. */
	private static void setLevel(String logger, String level) {
		assert EntryStoreClient.postRequest('/management/loggers/' + logger,
				JsonOutput.toJson([configuredLevel: level])).getResponseCode() == HTTP_NO_CONTENT
	}

	private static String cookieOf(String authCookie) {
		return 'auth_token=' + EntryStoreClient.cookieValueOf(authCookie)
	}

	/** @return the user that a /auth/user response names */
	private static String userOf(HttpURLConnection conn) {
		assert conn.getResponseCode() == HTTP_OK
		return JSON_PARSER.parseText(conn.inputStream.text)['user']
	}

	/** @return the names of the loggers below the prefix that wrote an event less severe than the level */
	private static Set<String> loggersBelow(List<LogEvent> events, String prefix, Level level) {
		return events.findAll { it.loggerName.startsWith(prefix) && !it.level.isMoreSpecificThan(level) }
				.collect { it.loggerName } as Set
	}

	/** @return the message of an event and its exception with all causes and suppressed exceptions */
	static String textOf(LogEvent event) {
		return TEXT_LAYOUT.toSerializable(event)
	}
}
