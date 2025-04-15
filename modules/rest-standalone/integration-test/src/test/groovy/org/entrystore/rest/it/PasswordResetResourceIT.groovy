package org.entrystore.rest.it

import com.icegreen.greenmail.util.GreenMail
import groovy.json.JsonOutput
import org.entrystore.rest.it.util.EntryStoreClient

import javax.mail.internet.InternetAddress

import static com.icegreen.greenmail.util.ServerSetupTest.SMTP
import static java.net.HttpURLConnection.*

class PasswordResetResourceIT extends BaseSpec {

    static GreenMail greenMail = new GreenMail(SMTP)

    def setup() {
        greenMail.start()
    }

    def cleanup() {
        greenMail.stop()
    }

    def "POST /auth/pwreset should send an email with generated token to an existing user"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'user@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'user@test.com',
                password: 'newPass12345'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_OK
        resetPasswordConn.getContentType().contains('text/html')
        resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
        def messages = greenMail.getReceivedMessages()
        messages.size() == 1
        def message = messages[0]
        message.getFrom().contains(new InternetAddress("info@meta.se"))
        message.getSubject() == "Password reset request"
        message.getAllRecipients().contains(new InternetAddress("user@test.com"))
        def messageContent = message.getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)
        token.length() == 16
    }

    def "POST /auth/pwreset should not send an email with generated token to a user who has no password set yet"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userHasNoPassword@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def requestBody = JsonOutput.toJson([
                email   : 'userHasNoPassword@test.com',
                password: 'newPass12345'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_OK
        resetPasswordConn.getContentType().contains('text/html')
        resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email to a non-existing user"() {
        given:
        def requestBody = JsonOutput.toJson([
                email   : 'userDoesNotExist@test.com',
                password: 'newPass12345'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_OK
        resetPasswordConn.getContentType().contains('text/html')
        resetPasswordConn.getInputStream().text.contains("<title>Password reset</title>")
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email with generated token when the password does not meet requirements"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetBadPassword@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetBadPassword@test.com',
                password: 'badPass'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email with generated token to an user with invalid email"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetBadEmail@']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetBadEmail@',
                password: 'newPass12345'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - email"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetNoEmail@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def requestBody = JsonOutput.toJson([
                password: 'newPass12345'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email with generated token when required parameters are missing - password"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetNoPassword@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def requestBody = JsonOutput.toJson([
                email: 'userResetNoPassword@test.com'
        ])

        when:
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)

        then:
        resetPasswordConn.getResponseCode() == HTTP_BAD_REQUEST
        def messages = greenMail.getReceivedMessages()
        messages.size() == 0
    }

    def "POST /auth/pwreset should not send an email with generated token for a disabled user"() {
        given:
        // create a user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetDisabled@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def editRequestBody = JsonOutput.toJson([
                password: 'newPass1234',
                disabled: 'true'
        ])

        when:
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, editRequestBody)

        then:
        editResourceConn.getResponseCode() == HTTP_NO_CONTENT
        def editResourceRespText = editResourceConn.getInputStream().text
        editResourceRespText == ''
        // fetch resource details again
        def resourceConn2 = EntryStoreClient.getRequest(resourceUri)
        resourceConn2.getResponseCode() == HTTP_OK
        resourceConn2.getContentType().contains('application/json')
        def resourceJson2 = JSON_PARSER.parseText(resourceConn2.getInputStream().text)
        resourceJson2['disabled'] == true
    }

    def "GET /store/auth/pwreset should confirm password reset for a valid token"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetConfirm@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetConfirm@test.com',
                password: 'newPass12345'
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK // needed for the required timeout to receive the email I guess
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getResponseCode() == HTTP_OK
        confirmConn.getContentType().contains('text/html')
        confirmConn.getInputStream().text.contains("<title>Password reset</title>")
        def messages = greenMail.getReceivedMessages()
        messages.size() == 2
        def message = messages[1]
        message.getFrom().contains(new InternetAddress("info@meta.se"))
        message.getSubject() == "Your password has been changed"
        message.getAllRecipients().contains(new InternetAddress("userresetconfirm@test.com"))
    }

    def "GET /store/auth/pwreset should not confirm password reset for an invalid token"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetInvalidToken@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetInvalidToken@test.com',
                password: 'newPass12345'
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        def token = "something123"

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getResponseCode() == HTTP_BAD_REQUEST
    }

    def "GET /store/auth/pwreset should not confirm password reset for a non-existing user"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetNotExisting@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetNotExisting@test.com',
                password: 'newPass12345'
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)

        def deleteResourceConn = EntryStoreClient.deleteRequest('/_principals/entry/' + userEntryId)
        deleteResourceConn.getResponseCode() == HTTP_NO_CONTENT
        def deletedEntryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        deletedEntryConn.getResponseCode() == HTTP_NOT_FOUND

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getResponseCode() == HTTP_NOT_FOUND
    }

    def "GET /store/auth/pwreset should not confirm password reset for already used token"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetAlreadyUsedToken@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetAlreadyUsedToken@test.com',
                password: 'newPass12345'
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)
        confirmConn.getResponseCode() == HTTP_OK

        when:
        def confirmAgainConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmAgainConn.getResponseCode() == HTTP_BAD_REQUEST
    }

    /*
    def "GET /store/auth/pwreset should not confirm password reset for an expired token"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetExpiredToken@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetExpiredToken@test.com',
                password: 'newPass12345'
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)
        def clock = Clock.fixed(Instant.parse("2022-01-01T12:00:00.00Z"), ZoneId.of("Europe/Stockholm"));

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getResponseCode() == HTTP_BAD_REQUEST
    }

    */

    def "GET /store/auth/pwreset should not confirm password reset for another token that was generated before a password change was successful"() {
        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetOldToken@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def requestBody = JsonOutput.toJson([
                email   : 'userResetOldToken@test.com',
                password: 'newPass12345'
        ])
        def oldResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        oldResetPasswordConn.getResponseCode() == HTTP_OK
        def oldMessageContent = greenMail.getReceivedMessages()[0].getContent()
        def oldStartIndex = oldMessageContent.toString().indexOf("?confirm") + 9
        def oldToken = oldMessageContent.toString().substring(oldStartIndex, oldStartIndex + 16)
        def newResetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        newResetPasswordConn.getResponseCode() == HTTP_OK
        def newMessageContent = greenMail.getReceivedMessages()[1].getContent()
        def newStartIndex = newMessageContent.toString().indexOf("?confirm") + 9
        def newToken = newMessageContent.toString().substring(newStartIndex, newStartIndex + 16)
        def newConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + newToken)
        newConfirmConn.getResponseCode() == HTTP_OK

        when:
        def oldConfirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + oldToken)

        then:
        oldConfirmConn.getResponseCode() == HTTP_BAD_REQUEST
    }

    def "GET /store/auth/pwreset should confirm password reset and redirect to provided permitted url"() {

        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetSuccessUrlPermitted@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def urlSuccess = "http://localhost:8181/123"
        def requestBody = JsonOutput.toJson([
                email   : 'userResetSuccessUrlPermitted@test.com',
                password: 'newPass12345',
                urlsuccess: urlSuccess
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getHeaderField("Location") == null
        confirmConn.getURL().toString() == urlSuccess
    }

    def "GET /store/auth/pwreset should confirm password reset and redirect to provided not permitted url"() {

        given:
        // create user
        def userParams = [graphtype: 'user']
        def userRequestResourceName = [name: 'userResetSuccessUrlNotPermitted@test.com']
        def userBody = JsonOutput.toJson([resource: userRequestResourceName])
        def userConnection = EntryStoreClient.postRequest('/_principals' + convertMapToQueryParams(userParams), userBody)
        def userEntryId = JSON_PARSER.parseText(userConnection.getInputStream().text)['entryId'].toString()
        def entryConn = EntryStoreClient.getRequest('/_principals/entry/' + userEntryId)
        def entryRespJson = JSON_PARSER.parseText(entryConn.getInputStream().text)
        def entryRespJsonKeys = (entryRespJson['info'] as Map).keySet().collect(it -> it.toString())
        def resourceUri = entryRespJsonKeys.find { it -> it.contains('resource') }
        def body = JsonOutput.toJson([
                password: 'oldPass123'
        ])
        def editResourceConn = EntryStoreClient.putRequest(resourceUri, body)
        def urlSuccess = "http://example.org/store/blabla/999"
        def requestBody = JsonOutput.toJson([
                email   : 'userResetSuccessUrlNotPermitted@test.com',
                password: 'newPass12345',
                urlsuccess: urlSuccess
        ])
        def resetPasswordConn = EntryStoreClient.postRequest('/auth/pwreset', requestBody)
        resetPasswordConn.getResponseCode() == HTTP_OK
        def messageContent = greenMail.getReceivedMessages()[0].getContent()
        def startIndex = messageContent.toString().indexOf("?confirm") + 9
        def token = messageContent.toString().substring(startIndex, startIndex + 16)

        when:
        def confirmConn = EntryStoreClient.getRequest('/auth/pwreset?confirm=' + token)

        then:
        confirmConn.getResponseCode() == HTTP_OK
        confirmConn.getURL().toString() == 'http://localhost:8181/auth/pwreset?confirm=' + token
    }

}
