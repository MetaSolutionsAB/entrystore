package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil
import spock.lang.Unroll

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class UserResourceIT extends BaseSpec {

	//static DateTimeFormatter dtf = new DateTimeFormatterBuilder()
	//	.appendPattern("yyyy-MM-dd'T'HH:mm:ss")
	//	.appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
	//	.toFormatter()
	static def newPassword = 'newPass12345'
	static def greenMail = new GreenMail(SMTP)
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('userForInfo@test.com', newPassword)
		EntryStoreClient.creds.put('userForInfoContext@test.com', newPassword)
		greenMail.start()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
		greenMail.stop()
	}

	@Unroll
	def 'GET /auth/user as "#requestUser" should respond with current user info'() {
		given:
		def languages = ['Accept-Language': 'fr-CH;q=0.9,en-US;q=0.7']

		when:
		def info = EntryStoreClient.getRequest('/auth/user', requestUser, null, languages)

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['id'] == expectedEntryId
		infoRespJson['user'] == expectedUsername.toLowerCase()
		infoRespJson['uri'] != null
		infoRespJson['clientAcceptLanguage'] != null
		infoRespJson['clientAcceptLanguage']['en-US'] == 0.7
		infoRespJson['clientAcceptLanguage']['fr-CH'] == 0.9
		// for Guest user 'authTokenExpires' field should not be present
		if (requestUser.isEmpty()) {
			assert infoRespJson['authTokenExpires'] == null
		} else {
			/*assert infoRespJson['authTokenExpires'] != null
			def authTokenExpires = LocalDateTime.parse(infoRespJson['authTokenExpires'].toString(), dtf)
			def now = LocalDateTime.now()
			assert ChronoUnit.HOURS.between(now, authTokenExpires) == 1*/
		}

		where:
		requestUser        | expectedUsername   | expectedEntryId
		''                 | 'guest'            | '_guest'
		'user'             | 'user'             | EntryStoreClient.createdEsUsers['user']['entryId']
		'userInAdminGroup' | 'userInAdminGroup' | EntryStoreClient.createdEsUsers['userInAdminGroup']['entryId']
		'admin'            | 'admin'            | '_admin'
	}

	def "GET /auth/user should return info about currently logged-in user"() {
		given:
		def username = 'userForInfo@test.com'
		def user = UserUtil.createUser(username)
		def entryId = user['entryId'].toString()
		def resourceUri = user['resourceUri'].toString()
		def requestBody = JsonOutput.toJson([
			password: 'newPass12345',
			language: 'SE'
		])
		assert EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def languages = ['Accept-Language': 'fr-CH;q=0.9,en-US;q=0.7']

		when:
		def info = EntryStoreClient.getRequest('/auth/user', username, null, languages)

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['id'] == entryId
		infoRespJson['user'] == username.toLowerCase()
		infoRespJson['language'] == 'SE'
		infoRespJson['clientAcceptLanguage'] != null
		infoRespJson['clientAcceptLanguage']['en-US'] == 0.7
		infoRespJson['clientAcceptLanguage']['fr-CH'] == 0.9
		//infoRespJson['authTokenExpires'] != null
		//def authTokenExpires = LocalDateTime.parse(infoRespJson['authTokenExpires'].toString(), dtf)
		//def now = LocalDateTime.now()
		//ChronoUnit.HOURS.between(now, authTokenExpires) == 23
	}

	def "GET /auth/user should return info about currently logged-in user including homecontext"() {
		given:
		def username = 'userForInfoContext@test.com'
		def requestBody = JsonOutput.toJson([
			firstname         : 'Home',
			lastname          : 'Context',
			email             : username,
			password          : newPassword,
			grecaptcharesponse: 'anything'
		])
		assert EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		assert EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		when:
		def info = EntryStoreClient.getRequest('/auth/user', username)

		then:
		info.getResponseCode() == HTTP_OK
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['homecontext'] != null
	}

}
