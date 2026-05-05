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
import groovy.json.JsonSlurper
import org.awaitility.core.ConditionEvaluationLogger
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil
import org.entrystore.rest.springboot.EntryStoreApplicationSpringBoot
import org.slf4j.LoggerFactory
import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.testcontainers.solr.SolrContainer
import org.testcontainers.containers.output.Slf4jLogConsumer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.spock.Testcontainers
import org.testcontainers.utility.DockerImageName
import org.testcontainers.utility.MountableFile
import spock.lang.Shared
import spock.lang.Specification

import java.util.concurrent.TimeUnit

import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NOT_FOUND
import static java.net.HttpURLConnection.HTTP_OK
import static java.nio.charset.StandardCharsets.UTF_8
import static org.awaitility.Awaitility.await

@Testcontainers
abstract class BaseSpec extends Specification {

	static {
		// Allow setting restricted HTTP headers (e.g. Origin, Access-Control-Request-Method)
		// on HttpURLConnection, needed for CORS integration tests.
		// Must be set before HttpURLConnection class is loaded.
		System.setProperty('sun.net.http.allowRestrictedHeaders', 'true')
	}

	static def log = LoggerFactory.getLogger(this.class)
	static def JSON_PARSER = new JsonSlurper()

	// Invariant for lifecycle-owning ITs (those that close this shared app and start their own
	// with non-default args, e.g. ZzzSamlLoginIT, ZzzCasLoginIT):
	//   1. Their class name MUST sort alphabetically AFTER all shared-app ITs (Zzz* prefix today),
	//      enforced by Failsafe runOrder=alphabetical in the integration-test pom.
	//   2. They MUST set appStarted=true in setupSpec after starting their own app, and MUST NOT
	//      reset it to false anywhere. If appStarted leaks back to false, the guard below re-runs
	//      the full init block (Solr + a shared app) between lifecycle-owning ITs, adding an
	//      extra Spring Boot start per CI run. The asserts in setupSpec catch the two invalid
	//      (appStarted, appInstance) state pairs.
	static def appStarted = false

	static ConfigurableApplicationContext appInstance

	// make sure Solr version matches the version used in the parent pom - 'solr.version' property
	@Shared
	def static solrContainer = new SolrContainer(DockerImageName.parse('solr:9.10.1'))
		.withEnv('SOLR_MODULES', 'analysis-extras')
		.withCopyFileToContainer(MountableFile.forClasspathResource('solr/'), '/entrystore-core/conf')

	def setupSpec() {
		if (!appStarted) {
			assert appInstance == null:
				'appStarted=false but appInstance!=null — an IT started an app without setting appStarted=true.'
			// clean cookies, in case there are some from a previous instance
			EntryStoreClient.cleanCookies()
			log.info('Starting Solr container')
			solrContainer.start()
			// below 2 lines allow to stream Solr container logs to the console
			def logConsumer = new Slf4jLogConsumer(log)
			solrContainer.followOutput(logConsumer)
			solrContainer.setWaitStrategy(Wait.forHttp('/solr/admin/cores').forStatusCode(200))

			// solrContainer.withCommand('solr-precreate') - to pre create core on startup, does not seem to work here,
			// as probably the Solr image creates its own ENTRYPOINT cmd that overrides the custom given command on startup
			solrContainer.execInContainer(
				'solr', 'create_core', '-c', 'entrystore-core', '-d', '/entrystore-core'
			)

			log.info('Starting common EntryStoreApp (without SSO login)')
			def args = ['--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core'] as String[]
			appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
			createCommonUserAccounts()
			appStarted = true
		} else {
			assert appInstance != null: 'Invariant #2 violated — see BaseSpec comment on appStarted.'
			log.info('EntryStoreApp already started')
		}
	}

	static void createCommonUserAccounts() {
		// Create all users except 'admin' ('admin' is created by the app itself)
		EntryStoreClient.creds.each {
			username, password -> {
				if (username != 'admin') {
					def isAdmin = EntryStoreClient.isAnAdmin(username)
					log.info('Creating ES user: {} (Admin: {})', username, isAdmin)
					def user = UserUtil.createUser(username, null, isAdmin)
					UserUtil.setUserPassword(user['resourceUri'].toString(), password)
					EntryStoreClient.createdEsUsers[username] = user
				}
			}
		}
	}

	/**
	 * Fetches requested context by given ID, if it does not exist then creates a new context with that ID.
	 * Expects contextId to be present in the `data` argument.
	 * @param data Map with required param `contextId`, optional param `name`
	 * @return
	 */
	def getOrCreateContext(Map data) {
		assert data['contextId'] != null
		assert data['contextId'].toString().length() > 0
		def connection = EntryStoreClient.getRequest('/_contexts/entry/' + data['contextId'])
		if (connection.getResponseCode() == HTTP_NOT_FOUND) {
			createContext(data)
		}
	}

	/**
	 *
	 * @param data Map with data that will be sent in the request URL to the endpoint
	 * @return ID of the created group
	 */
	def createContext(Map data) {
		def connection = EntryStoreClient.postRequest('/_principals/groups' + convertMapToQueryParams(data))
		assert connection.getResponseCode() == HTTP_CREATED
		return connection.getHeaderField('Location').find(/\/_principals\/entry\/([0-9A-Za-z]+)$/) { match, id -> id }
	}

	/**
	 *
	 * @param data a key-val map to be converted
	 * @return a string in form of "?key1=value1&key2=value2&..."; or empty string for empty map
	 */
	def static convertMapToQueryParams(Map<String, String> data) {
		return (data.size() == 0) ? '' : '?' + data.collect { k, v -> k + '=' + URLEncoder.encode(v, UTF_8) }.join('&')
	}

	/**
	 * Fetches requested entry by given ID, if it does not exist then creates a new entry with that ID.
	 * Expects "id" key to be present in the `params` argument.
	 *
	 * @param contextId under which to create the entry
	 * @param params key-value map which will be send in the request URL, e.g. [entrytype: 'link', resource: '...Url...', id: 'entryId']
	 * @param body key-value map which will be send in the request body, e.g. [resource: 'someText']
	 * @return entry ID from the response
	 */
	def getOrCreateEntry(String contextId, Map params, Map body = [:]) {
		def entryId = params['id']
		assert entryId != null
		assert entryId.toString().length() > 0
		def entryConn = EntryStoreClient.getRequest('/' + contextId + '/entry/' + entryId)
		if (entryConn.getResponseCode() == HTTP_OK) {
			entryConn.getContentType().contains('application/json')
			def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
			entryRespJson['entryId'] != null
			return entryRespJson['entryId'].toString()
		} else if (entryConn.getResponseCode() == HTTP_NOT_FOUND) {
			return createEntry(contextId, params, body)
		} else {
			assert false // unexpected response
		}
	}

	/**
	 *
	 * @param contextId under which to create the entry
	 * @param params key-value map which will be send in the request URL, e.g. [entrytype: 'link', resource: '...Url...', id: 'entryId']
	 * @param body key-value map which will be send in the request body, e.g. [resource: 'someText']
	 * @return created entry ID
	 */
	def static createEntry(String contextId, Map params, Map body = [:]) {
		def bodyJson = JsonOutput.toJson(body)
		def connection = EntryStoreClient.postRequest('/' + contextId + convertMapToQueryParams(params), bodyJson)
		assert connection.getResponseCode() == HTTP_CREATED
		assert connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		assert responseJson['entryId'] != null
		assert responseJson['entryId'].toString().length() > 0
		return responseJson['entryId'].toString()
	}

	Map getSolrStatus() {
		def connection = EntryStoreClient.getRequest('/management/status/extended')
		assert connection.getResponseCode() == HTTP_OK
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		assert responseJson?.solr != null : "Missing 'solr' field in /management/status/extended response"
		return responseJson.solr as Map
	}

	private static final Closure<Boolean> SOLR_IDLE = { Map status ->
		status.postQueueSize == 0 && (status.indexingContexts as Collection)?.isEmpty()
	}

	def waitForSolrProcessing() {
		await()
			.conditionEvaluationListener(new ConditionEvaluationLogger(log::info))
			.pollInterval(50, TimeUnit.MILLISECONDS)
			.atMost(30, TimeUnit.SECONDS)
			// separate supplier and predicate for better await logging
			.until({ getSolrStatus() }, SOLR_IDLE)
	}

	protected static void stopPreexistingAppIfRunning() {
		if (appInstance != null) {
			log.info('Stopping pre-existing ES instance')
			try {
				appInstance.close()
			} finally {
				appInstance = null
				EntryStoreClient.cleanCookies()
			}
		}
	}
}
