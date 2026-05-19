package org.entrystore.rest.it

import groovy.json.JsonOutput
import org.apache.logging.log4j.Level
import org.apache.logging.log4j.LogManager
import org.apache.logging.log4j.core.LogEvent
import org.apache.logging.log4j.core.Logger
import org.apache.logging.log4j.core.LoggerContext
import org.apache.logging.log4j.core.appender.AbstractAppender
import org.apache.logging.log4j.core.config.Property
import org.apache.logging.log4j.core.layout.PatternLayout
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.springboot.controller.AppExceptionHandler

import java.util.concurrent.CopyOnWriteArrayList

import static java.net.HttpURLConnection.HTTP_BAD_METHOD
import static java.net.HttpURLConnection.HTTP_CONFLICT
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class ManagementLoggingIT extends BaseSpec {

	def static contextId = '_contexts'
	def static entryId = 'duplicatedEntryId'
	def static resourceUrl = 'https://bbc.co.uk'
	def static appExceptionHandlerLogger = 'org.entrystore.rest.springboot.controller.AppExceptionHandler'

	def cleanupSpec() {
		// Safety net in case the per-test cleanup didn't run.
		assert EntryStoreClient.postRequest('/management/loggers/' + appExceptionHandlerLogger,
				JsonOutput.toJson([configuredLevel: 'DEBUG'])).getResponseCode() == HTTP_NO_CONTENT
	}

	// logging helper class
	static class MemoryAppender extends AbstractAppender {
		private final List<LogEvent> events = new CopyOnWriteArrayList<>()

		MemoryAppender(String name) {
			super(name, null, PatternLayout.createDefaultLayout(), false, Property.EMPTY_ARRAY)
		}

		@Override
		void append(LogEvent event) {
			events.add(event.toImmutable())
		}

		List<LogEvent> getEvents() {
			return events
		}
	}

	def "GET /management/loggers as Guest should respond with UNAUTHORIZED"() {
		when: 'an unauthenticated user requests the loggers endpoint'
		def conn = EntryStoreClient.getRequest('/management/loggers', null)

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "GET /management/loggers as a non-admin user should respond with FORBIDDEN"() {
		when: 'an authenticated non-admin user requests the loggers endpoint'
		def conn = EntryStoreClient.getRequest('/management/loggers', 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
		conn.errorStream.text.contains('"error":"Forbidden"')
	}

	def "POST /management/loggers/{name} as Guest should respond with UNAUTHORIZED"() {
		when: 'an unauthenticated user attempts to change a logger level'
		def conn = EntryStoreClient.postRequest('/management/loggers/ROOT',
				JsonOutput.toJson([configuredLevel: 'INFO']), null)

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		conn.errorStream.text.contains('"error":"Unauthorized"')
	}

	def "POST /management/loggers/{name} as a non-admin user should respond with FORBIDDEN"() {
		when: 'an authenticated non-admin user attempts to change a logger level'
		def conn = EntryStoreClient.postRequest('/management/loggers/ROOT',
				JsonOutput.toJson([configuredLevel: 'INFO']), 'user')

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
		conn.errorStream.text.contains('"error":"Forbidden"')
	}

	def "GET /management/loggers as admin should return the logger list"() {
		when: 'an admin requests the loggers endpoint'
		def conn = EntryStoreClient.getRequest('/management/loggers')

		then:
		conn.getResponseCode() == HTTP_OK
		def body = JSON_PARSER.parseText(conn.inputStream.text)
		body.loggers != null
		body.loggers.size() > 0
		// Actuator always exposes the root logger under the literal key 'ROOT'
		body.loggers.containsKey('ROOT')
	}

	def "POST /management/loggers/{name} should update the logger level"() {
		given:
		// add in-memory appender to AppExceptionHandler class, so we can capture the log events
		def ctx = (LoggerContext) LogManager.getContext(false)
		def logger = (Logger) ctx.getLogger(AppExceptionHandler.class)
		def appender = new MemoryAppender("testAppender")
		appender.start()
		logger.addAppender(appender)

		// Trigger a 405 response - should be logged at DEBUG level
		assert EntryStoreClient.putRequest('/management/status').getResponseCode() == HTTP_BAD_METHOD

		assert appender.events.count {
			it.level == Level.DEBUG &&
					it.message.formattedMessage.contains("General ErrorResponse Exception of type 'org.springframework.web.HttpRequestMethodNotSupportedException' at endpoint '/management/status'. Error: ")
		} == 1

		// Trigger a 409 response - should be logged at WARN level
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		getOrCreateEntry(contextId, params)
		assert EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params)).getResponseCode() == HTTP_CONFLICT

		assert appender.events.count {
			it.level == Level.WARN &&
					it.message.formattedMessage.contains("Entry with provided ID already exists. EntryID: 'duplicatedEntryId'")
		} == 1

		when: 'AppExceptionHandler level is raised to INFO via Actuator'
		def conn = EntryStoreClient.postRequest('/management/loggers/' + appExceptionHandlerLogger,
				JsonOutput.toJson([configuredLevel: 'INFO']))

		then:
		conn.getResponseCode() == HTTP_NO_CONTENT

		// Direct verification that Actuator applied the level (not just that POST returned 204).
		def updatedLogger = JSON_PARSER.parseText(EntryStoreClient.getRequest(
				'/management/loggers/' + appExceptionHandlerLogger).inputStream.text)
		updatedLogger.configuredLevel == 'INFO'
		updatedLogger.effectiveLevel == 'INFO'

		// Triggering a 405 exception should not be logged now, as it is a DEBUG level event, but we configured INFO level
		EntryStoreClient.putRequest('/management/status').getResponseCode() == HTTP_BAD_METHOD

		appender.events.count {
			it.level == Level.DEBUG &&
					it.message.formattedMessage.contains("General ErrorResponse Exception of type 'org.springframework.web.HttpRequestMethodNotSupportedException' at endpoint '/management/status'. Error: ")
		} == 1

		// Trigger a 409 response - should still be logged at WARN level
		EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params)).getResponseCode() == HTTP_CONFLICT

		appender.events.count {
			it.level == Level.WARN &&
					it.message.formattedMessage.contains("Entry with provided ID already exists. EntryID: 'duplicatedEntryId'")
		} == 2

		cleanup:
		logger.removeAppender(appender)
		appender.stop()
		assert EntryStoreClient.postRequest('/management/loggers/' + appExceptionHandlerLogger,
				JsonOutput.toJson([configuredLevel: 'DEBUG'])).getResponseCode() == HTTP_NO_CONTENT
	}
}
