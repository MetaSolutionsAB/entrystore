package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient
import org.entrystore.rest.it.util.UserUtil

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST
import static java.net.HttpURLConnection.HTTP_FORBIDDEN
import static java.net.HttpURLConnection.HTTP_OK
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED

class MessageIT extends BaseSpec {

	static def newPassword = 'newPass12345'
	static def greenMail = new GreenMail(SMTP)
	static def genericCredsClone = [:]

	def setupSpec() {
		genericCredsClone = EntryStoreClient.creds.clone()
		EntryStoreClient.creds.put('sender@test.com', newPassword)
		EntryStoreClient.creds.put('msgReplyTo@test.com', newPassword)
		greenMail.start()
	}

	def cleanup() {
		greenMail.purgeEmailFromAllMailboxes()
	}

	def cleanupSpec() {
		EntryStoreClient.creds = genericCredsClone
		greenMail.stop()
	}

	def "POST /message should send an email to an existing user"() {
		given:
		def recipientUsername = 'msgRecipient@test.com'
		UserUtil.createUser(recipientUsername)
		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Test Subject',
			to       : recipientUsername,
			body     : '<p>Hello World</p>'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)

		then:
		// Spring Boot returns HTTP_OK; legacy Restlet returned HTTP_NO_CONTENT
		conn.getResponseCode() == HTTP_OK
		def messages = greenMail.getReceivedMessages()
		messages.length == 1
		messages[0].getSubject() == 'Test Subject'
		messages[0].getContent().toString().contains('Hello World')
	}

	def "POST /message should return 401 for guest user"() {
		given:
		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Test Subject',
			to       : 'someone@test.com',
			body     : 'Hello'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody, '')

		then:
		conn.getResponseCode() == HTTP_UNAUTHORIZED
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /message should return 403 for unknown recipient"() {
		given:
		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Test Subject',
			to       : 'nonexistent@test.com',
			body     : 'Hello'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)

		then:
		conn.getResponseCode() == HTTP_FORBIDDEN
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /message should return 400 for unsupported transport"() {
		given:
		def recipientUsername = 'msgTransport@test.com'
		UserUtil.createUser(recipientUsername)
		def requestBody = JsonOutput.toJson([
			transport: 'sms',
			subject  : 'Test Subject',
			to       : recipientUsername,
			body     : 'Hello'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /message should return 400 when required fields are missing"() {
		given:
		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Test Subject'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)

		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		greenMail.getReceivedMessages().length == 0
	}

	def "POST /message should set Reply-To when sender has email-format username"() {
		given:
		def senderUsername = 'sender@test.com'
		def sender = UserUtil.createUser(senderUsername)
		UserUtil.setUserPassword(sender['resourceUri'].toString(), newPassword)
		greenMail.purgeEmailFromAllMailboxes()
		def recipientUsername = 'msgReplyTo@test.com'
		UserUtil.createUser(recipientUsername)

		// Authorize the sender
		def authConn = EntryStoreClient.postRequest('/auth/cookie',
			'auth_username=' + senderUsername + '&auth_password=' + newPassword, '', 'application/x-www-form-urlencoded')
		assert authConn.getResponseCode() == HTTP_OK

		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Reply-To Test',
			to       : recipientUsername,
			body     : 'Check reply-to'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody, senderUsername)

		then:
		// Spring Boot returns HTTP_OK; legacy Restlet returned HTTP_NO_CONTENT
		conn.getResponseCode() == HTTP_OK
		def messages = greenMail.getReceivedMessages()
		messages.length == 1
		def message = messages[0]
		message.getReplyTo()*.toString().contains(senderUsername.toLowerCase())
		message.getAllRecipients()*.toString().contains(recipientUsername)
	}

	def "POST /message should sanitize HTML in email body and subject"() {
		given:
		def recipientUsername = 'msgSanitize@test.com'
		UserUtil.createUser(recipientUsername)
		def requestBody = JsonOutput.toJson([
			transport: 'email',
			subject  : 'Test <script>alert("xss")</script> Subject',
			to       : recipientUsername,
			body     : '<p>Hello</p><script>alert("xss")</script><a href="javascript:alert(1)">click</a><a href="https://safe.com">safe</a>'
		])

		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)

		then:
		conn.getResponseCode() == HTTP_OK
		def messages = greenMail.getReceivedMessages()
		messages.length == 1
		def content = messages[0].getContent().toString()
		content.contains('<p>Hello</p>')
		!content.contains('<script>')
		!content.contains('javascript:')
		content.contains('href="https://safe.com"')
		!messages[0].getSubject().contains('<script>')
		messages[0].getSubject().contains('Test')
		messages[0].getSubject().contains('Subject')
	}

	def "POST /message should return 400 for malformed JSON body"() {
		given:
		def requestBody = 'this is not json'
		when:
		def conn = EntryStoreClient.postRequest('/message', requestBody)
		then:
		conn.getResponseCode() == HTTP_BAD_REQUEST
		greenMail.getReceivedMessages().length == 0
	}
}
