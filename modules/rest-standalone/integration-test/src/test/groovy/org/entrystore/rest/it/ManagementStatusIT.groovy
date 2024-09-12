package org.entrystore.rest.it

import org.entrystore.rest.standalone.EntryStoreApplicationStandaloneJetty
import spock.lang.Specification

import static java.net.HttpURLConnection.HTTP_OK

class ManagementStatusIT extends Specification {

    def "Status endpoint should reply with status"() {
        given:
        def args = ['-c', 'file:src/test/resources/entrystore-it.properties'] as String[]
        EntryStoreApplicationStandaloneJetty.main(args)
        def connection = (HttpURLConnection) new URI('http://localhost:8181/management/status').toURL().openConnection()

        when:
        connection.connect()

        then:
        connection.getResponseCode() == HTTP_OK
        connection.getContentType().contains('text/plain')
        connection.getInputStream().text == 'DOWN'
    }
}
