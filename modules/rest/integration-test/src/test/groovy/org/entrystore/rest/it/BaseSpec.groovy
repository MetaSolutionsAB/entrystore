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

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.stubbing.StubMapping
import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import groovy.transform.PackageScope
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

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse
import static com.github.tomakehurst.wiremock.client.WireMock.post
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options
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

	// Not in java.net.HttpURLConnection's HTTP_* constants (RFC 6585)
	static final int HTTP_TOO_MANY_REQUESTS = 429

	// Invariant for lifecycle-owning ITs (those that close this shared app and start their own
	// with non-default args, e.g. ZzzSamlLoginIT, ZzzCasLoginIT):
	//   1. Their class name MUST sort alphabetically AFTER all shared-app ITs (Zzz* prefix today),
	//      enforced by Failsafe runOrder=alphabetical in the integration-test pom.
	//   2. They MUST set appStarted=true in setupSpec after starting their own app (startOwnedApp
	//      does this), and MUST NOT reset it to false anywhere. If appStarted leaks back to false, the guard below re-runs
	//      the full init block (Solr + a shared app) between lifecycle-owning ITs, adding an
	//      extra Spring Boot start per CI run. The asserts in setupSpec catch the two invalid
	//      (appStarted, appInstance) state pairs.
	static def appStarted = false

	static ConfigurableApplicationContext appInstance

	// The data folder this run validated as safe to own (empty/absent at startup) and is therefore allowed
	// to delete. Published by guardAndClaimDataFolder before the app starts, then read by
	// DataFolderCleanupExtension.executionStop to clean up once after the whole run. Null if no shared-app
	// IT ran.
	static volatile File ownedDataFolder

	// make sure Solr version matches the version used in the parent pom - 'solr.version' property
	@Shared
	def static solrContainer = new SolrContainer(DockerImageName.parse('solr:10.0.0'))
		.withEnv('SOLR_MODULES', 'analysis-extras')
		.withCopyFileToContainer(MountableFile.forClasspathResource('solr/'), '/entrystore-core/conf')

	// Shared WireMock server used to stub external HTTP services so ITs never hit the live network.
	static WireMockServer wireMockServer = new WireMockServer(
		options().dynamicPort().usingFilesUnderClasspath('wiremock'))

	static String getRecaptchaStubUrl() {
		return 'http://localhost:' + wireMockServer.port() + '/recaptcha/api/siteverify'
	}

	static StubMapping registerRecaptchaFailStub(int status) {
		return wireMockServer.stubFor(
			post(urlPathEqualTo('/recaptcha/api/siteverify'))
				.atPriority(1)
				.willReturn(aResponse().withStatus(status)))
	}

	def setupSpec() {
		if (!appStarted) {
			assert appInstance == null:
				'appStarted=false but appInstance!=null — an IT started an app without setting appStarted=true.'
			// Fail fast (before starting anything) if the configured data folder is not safe for the suite
			// to own (must be empty/absent), and publish it for cleanup. The guarded path is cross-checked
			// against the running app's resolved value below. See guardAndClaimDataFolder.
			// App populates folder with data on boot, so can't verify folder is empty/absent once the app has started.
			def guardedDataFolder = guardAndClaimDataFolder()
			// clean cookies, in case there are some from a previous instance
			EntryStoreClient.cleanCookies()
			log.info('Starting WireMock for external-service stubs')
			wireMockServer.start()
			assert wireMockServer.listAllStubMappings().mappings.any {
				it.request.urlPath == '/recaptcha/api/siteverify'
			}: 'Default reCAPTCHA stub failed to load from classpath wiremock/mappings/'
			log.info('Starting Solr container')
			solrContainer.start()
			// below 2 lines allow to stream Solr container logs to the console
			def logConsumer = new Slf4jLogConsumer(log)
			solrContainer.followOutput(logConsumer)
			solrContainer.setWaitStrategy(Wait.forHttp('/solr/admin/cores').forStatusCode(200))

			// solrContainer.withCommand('solr-precreate') - to pre create core on startup, does not seem to work here,
			// as probably the Solr image creates its own ENTRYPOINT cmd that overrides the custom given command on startup
			solrContainer.execInContainer(
				'solr', 'create', '-c', 'entrystore-core', '-d', '/entrystore-core'
			)

			log.info('Starting common EntryStoreApp (without SSO login)')
			def args = [
				'--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core',
				'--entrystore.auth.recaptcha.url=' + getRecaptchaStubUrl(),
				// Inject the dynamic WireMock origin into the DELETE whitelist; the port is only known at runtime.
				'--entrystore.proxy.remote-resource.delete.whitelist.1=http://localhost:' + wireMockServer.port()
			] as String[]
			appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
			// Verify the folder we guarded/armed before startup is the one the running app actually uses,
			// guarding against override logic resolving entrystore.data.folder to a different path.
			verifyGuardedDataFolderUnchanged(guardedDataFolder, appInstance.getEnvironment().getProperty('entrystore.data.folder'))
			createCommonUserAccounts()
			appStarted = true
		} else {
			assert appInstance != null: 'Invariant #2 violated — see BaseSpec comment on appStarted.'
			log.info('EntryStoreApp already started')
		}
	}

	// Reset the WireMock request journal before each feature so per-test verify(N, ...) assertions
	// count only this feature's requests, not the JVM-wide accumulation across prior specs.
	// Does not clear stubs — classpath defaults and per-test atPriority(1) overrides remain intact.
	def setup() {
		wireMockServer.resetRequests()
	}

	/**
	 * Fail-fast guard for the IT data folder, run BEFORE the app starts, that also publishes the folder for
	 * cleanup. The store is in-memory (fresh each run) while the data folder is a fixed on-disk path, so
	 * without cleanup files from a previous run survive on disk.
	 *
	 * <p>Safety: {@code entrystore.data.folder} is operator-configurable and could be pointed at an
	 * important directory by mistake. The suite both writes test data into this folder and deletes it after
	 * the run, so it must own the folder. Ownership is proven by the folder being empty or absent at
	 * startup; otherwise the run fails fast here, before anything is started or written, rather than
	 * pollute or delete a directory it does not own. The value is read from the classpath properties so the
	 * check can run before the app exists; {@link #verifyGuardedDataFolderUnchanged} then confirms, once the
	 * app is up, that this is the same path the app actually resolved. The validated path is published in
	 * {@link #ownedDataFolder} for {@code DataFolderCleanupExtension} to delete at the end of the run.
	 *
	 * @return the canonical guarded folder, for the post-start cross-check
	 */
	private static File guardAndClaimDataFolder() {
		def configured = loadItProperty('entrystore.data.folder')
		if (!configured) {
			throw new IllegalStateException(
				"entrystore.data.folder is not configured for the integration tests; it must be set in " +
				"entrystore-it.properties so the suite has an isolated, disposable data folder.")
		}
		def dir = toDataFolderFile(configured)
		assertEmptyOrAbsentDir(dir)

		// Empty/absent → this run owns it. Publish it for DataFolderCleanupExtension to delete once the
		// whole run finishes (executionStop, inside the live test JVM, so its log is captured by Maven).
		ownedDataFolder = canonicalFile(dir)
		return ownedDataFolder
	}

	/**
	 * Resolves a configured {@code entrystore.data.folder} value to a {@link File}, stripping a
	 * {@code file:}/{@code file://} scheme prefix. Shared by the pre-start guard and the post-start
	 * cross-check so both resolve the configured value identically.
	 */
	@PackageScope
	static File toDataFolderFile(String configured) {
		return new File(configured.replaceFirst('^file://', '').replaceFirst('^file:', ''))
	}

	/**
	 * Throws unless {@code dir} is safe for the suite to own: it must be absent, or an empty and listable
	 * directory. A regular file, a directory that cannot be listed, or a non-empty directory each fail fast
	 * — the suite both writes test data into this folder and deletes it after the run, so an unowned or
	 * pre-populated path could pollute or delete real data.
	 */
	@PackageScope
	static void assertEmptyOrAbsentDir(File dir) {
		if (!dir.exists()) {
			return
		}
		if (!dir.isDirectory()) {
			throw new IllegalStateException(
				"Refusing to run integration tests: data folder '" + dir.absolutePath + "' exists but is not " +
				"a directory. Point entrystore.data.folder at a directory the suite can own.")
		}
		def children = dir.list()
		if (children == null) {
			throw new IllegalStateException(
				"Refusing to run integration tests: data folder '" + dir.absolutePath + "' cannot be listed " +
				"(I/O error or permissions), so its emptiness cannot be verified. Fix the config or permissions.")
		}
		if (children.length > 0) {
			throw new IllegalStateException(
				"Refusing to run integration tests: data folder '" + dir.absolutePath + "' is not empty " +
				"at startup. The suite writes test data here and deletes it after the run, so it must start " +
				"empty (or absent) to prove the run owns it — this guards a misconfigured " +
				"entrystore.data.folder from polluting or deleting real data. Clear it or fix the config.")
		}
	}

	/**
	 * Confirms the running app resolved {@code entrystore.data.folder} to the same directory guarded before
	 * startup. If override logic changed it, the pre-start emptiness guard and the shutdown cleanup would
	 * target the wrong directory, so the run fails rather than continue against an unguarded folder.
	 */
	private static void verifyGuardedDataFolderUnchanged(File guardedDataFolder, String effectiveDataFolder) {
		def effective = effectiveDataFolder == null ? null : canonicalFile(toDataFolderFile(effectiveDataFolder))
		if (effective != guardedDataFolder) {
			throw new IllegalStateException(
				"entrystore.data.folder mismatch: guarded '" + guardedDataFolder + "' before startup, but the " +
				"running app resolved it to '" + effective + "'. The emptiness guard and shutdown cleanup would " +
				"target the wrong directory — refusing to continue. Align the configuration.")
		}
	}

	@PackageScope
	static File canonicalFile(File f) {
		try {
			return f.canonicalFile
		} catch (IOException e) {
			log.warn('Could not canonicalize data folder path {} ({}); falling back to absolute path', f, e.message, e)
			return f.absoluteFile
		}
	}

	private static String loadItProperty(String key) {
		def stream = BaseSpec.classLoader.getResourceAsStream('entrystore-it.properties')
		if (stream == null) {
			return null
		}
		def props = new Properties()
		stream.withCloseable { props.load(it) }
		return props.getProperty(key)
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
	 * Creates a request query string ?key=value&… with encoded values
	 *
	 * @param data a key-val map to be converted
	 * @return a string in form of "?key1=value1&key2=value2&..."; or empty string for empty map
	 */
	def static convertMapToQueryParams(Map<String, String> data) {
		return (data.size() == 0) ? '' : '?' + createFormBody(data)
	}

	/**
	 * Creates a form body for www-form-urlencoded requests as a concatenated key=value&… string, with encoded values
	 *
	 * @param params input
	 * @return a String in form of key1=value1&key2=value2&...; empty for an empty map
	 */
	def static createFormBody(Map<String, String> params) {
		return params
			.collect { k, v -> k + '=' + URLEncoder.encode(v, UTF_8) }
			.join('&')
	}

	/**
	 * Extracts the 16-character confirmation token following the '?confirm=' marker in a received
	 * email (sign-up and password-reset confirmation mails share this link format).
	 *
	 * @param mail the GreenMail instance holding the message
	 * @param index index into the received-messages array (defaults to the first message)
	 */
	protected static String extractConfirmationToken(GreenMail mail, int index = 0) {
		def content = mail.getReceivedMessages()[index].getContent().toString()
		def marker = '?confirm='
		def markerIndex = content.indexOf(marker)
		assert markerIndex != -1: 'confirmation mail carries no ?confirm= link'
		def start = markerIndex + marker.length()
		return content.substring(start, start + 16)
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

	/**
	 * Starts a lifecycle-owned EntryStore app for specs that need non-default args, prepending the
	 * shared Solr URL and upholding invariant #2 (sets appStarted=true, see the comment on it).
	 * Callers stop the shared app via stopPreexistingAppIfRunning() first; per-spec infrastructure
	 * (GreenMail, Keycloak) stays in the callers.
	 */
	protected static void startOwnedApp(List<String> extraArgs) {
		assert appInstance == null: 'call stopPreexistingAppIfRunning() before startOwnedApp'
		def args = (['--entrystore.solr.url=http://localhost:' + solrContainer.getSolrPort() + '/solr/entrystore-core']
			+ extraArgs) as String[]
		appInstance = SpringApplication.run(EntryStoreApplicationSpringBoot.class, args)
		appStarted = true
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
