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
import org.entrystore.rest.standalone.springboot.controller.AppExceptionHandler

import java.util.concurrent.CopyOnWriteArrayList

import static java.net.HttpURLConnection.HTTP_ACCEPTED
import static java.net.HttpURLConnection.HTTP_BAD_METHOD
import static java.net.HttpURLConnection.HTTP_CONFLICT

class ManagementLoggingIT extends BaseSpec {

	def static contextId = '_contexts'
	def static entryId = 'duplicatedEntryId'
	def static resourceUrl = 'https://bbc.co.uk'

	def cleanupSpec() {
		// Set original logging config
		def config = [
				level: 'info',
				packages: [
						'org.entrystore.rest.standalone.springboot.controller.AppExceptionHandler': 'debug'
				]]

		assert EntryStoreClient.putRequest('/management/logging', JsonOutput.toJson(config)).getResponseCode() == HTTP_ACCEPTED
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

	def "PUT /management/logging should update logging configuration"() {
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
					it.message.formattedMessage.contains("General ErrorResponse Exception of 'org.springframework.web.HttpRequestMethodNotSupportedException'")
		} == 1

		// Trigger a 409 response - should be logged at WARN level
		def params = [entrytype: 'link', resource: resourceUrl, id: entryId]
		getOrCreateEntry(contextId, params)
		assert EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params)).getResponseCode() == HTTP_CONFLICT

		assert appender.events.count {
			it.level == Level.WARN &&
					it.message.formattedMessage.contains("Entry with provided ID already exists. EntryID: 'duplicatedEntryId'")
		} == 1


		// New logging config to log only INFO level for AppExceptionHandler
		def config = [
				level: 'warn',
				packages: [
						'org.entrystore.rest.standalone.springboot.controller.AppExceptionHandler': 'info'
				]]

		when: 'New logging config is accepted'
		EntryStoreClient.putRequest('/management/logging', JsonOutput.toJson(config)).getResponseCode() == HTTP_ACCEPTED

		then:
		// Triggering a 405 exception should not be logged now, as it is a DEBUG level event, but we configured INFO level
		EntryStoreClient.putRequest('/management/status').getResponseCode() == HTTP_BAD_METHOD

		appender.events.count {
			it.level == Level.DEBUG &&
					it.message.formattedMessage.contains("General ErrorResponse Exception of 'org.springframework.web.HttpRequestMethodNotSupportedException'")
		} == 1

		// Trigger a 409 response - should still be logged at WARN level
		EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params)).getResponseCode() == HTTP_CONFLICT

		appender.events.count {
			it.level == Level.WARN &&
					it.message.formattedMessage.contains("Entry with provided ID already exists. EntryID: 'duplicatedEntryId'")
		} == 2

	}
}
