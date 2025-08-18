package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.joda.time.format.DateTimeFormat
import org.joda.time.format.DateTimeFormatter

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_CREATED
import static java.net.HttpURLConnection.HTTP_NO_CONTENT
import static java.net.HttpURLConnection.HTTP_OK

class UserResourceIT extends BaseSpec {

	static DateTimeFormatter dtf = DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSSSSS")
	static def newPassword = 'newPass12345'
	static GreenMail greenMail = new GreenMail(SMTP)
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

	def "GET /auth/user should return info about currently logged-in user"() {
		given:
		// create user
		def userParams = [graphtype: 'user']
		def userRequestResourceName = [name: 'userForInfo@test.com']
		def userBody = JsonOutput.toJson([resource: userRequestResourceName])
		def connection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
		connection.getResponseCode() == HTTP_CREATED
		connection.getContentType().contains('application/json')
		def responseJson = JSON_PARSER.parseText(connection.getInputStream().text)
		def entryId = responseJson['entryId'].toString()
		def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + entryId)
		def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
		def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
		def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
		def requestBody = JsonOutput.toJson([
				password: 'newPass12345',
				language: 'SE'
		])
		EntryStoreClient.putRequest(resourceUri, requestBody).getResponseCode() == HTTP_NO_CONTENT
		def languages = new HashMap<String, String>()
		languages.put('Accept-Language', 'fr-CH;q=0.9,en-US;q=0.7')

		when:
		def info = EntryStoreClient.getRequest('/auth/user', 'userForInfo@test.com', null, languages)

		then:
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['id'] == entryId
		infoRespJson['user'] == 'userForInfo@test.com'.toLowerCase()
		infoRespJson['language'] == 'SE'
		infoRespJson['clientAcceptLanguage'] != null
		infoRespJson['clientAcceptLanguage']['en-US'] == 0.7
		infoRespJson['clientAcceptLanguage']['fr-CH'] == 0.9
		//infoRespJson['authTokenExpires'] != null
		//def authTokenExpires = dtf.parseDateTime(infoRespJson['authTokenExpires'].toString())
		//def now = dtf.parseDateTime(LocalDateTime.now().toString())
		//Hours.hoursBetween(now, authTokenExpires).hours == 23
	}

	def "GET /auth/user should return info about currently logged-in user including homecontext"() {
		given:
		def requestBody = JsonOutput.toJson([
				firstname         : 'Home',
				lastname          : 'Context',
				email             : 'userForInfoContext@test.com',
				password          : newPassword,
				grecaptcharesponse: 'anything'
		])
		EntryStoreClient.postRequest('/auth/signup', requestBody).getResponseCode() == HTTP_OK
		def messageContent = greenMail.getReceivedMessages()[0].getContent()
		def startIndex = messageContent.toString().indexOf("?confirm") + 9
		def token = messageContent.toString().substring(startIndex, startIndex + 16)
		EntryStoreClient.getRequest('/auth/signup?confirm=' + token).getResponseCode() == HTTP_CREATED

		def info = EntryStoreClient.getRequest('/auth/user', 'userForInfoContext@test.com')
		def infoRespJson = JSON_PARSER.parseText(info.getInputStream().text)
		infoRespJson['homecontext'] != null
		infoRespJson['homecontext'] == 1

	}

}
