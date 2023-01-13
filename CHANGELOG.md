# ENTRYSTORE CHANGELOG

## Version 5.0.0 (2022-01-13)

### Improvement

[ENTRYSTORE-367](https://metasolutions.atlassian.net/browse/ENTRYSTORE-367) Migrate to RDF4J

[ENTRYSTORE-491](https://metasolutions.atlassian.net/browse/ENTRYSTORE-491) Provide different file extensions when downloading in different RDF formats

[ENTRYSTORE-552](https://metasolutions.atlassian.net/browse/ENTRYSTORE-552) Add support for SPARQLResultsCSVWriter in SparqlResource

[ENTRYSTORE-571](https://metasolutions.atlassian.net/browse/ENTRYSTORE-571) Replace com.github.jsonld-java with RDF4J native JSON-LD support

[ENTRYSTORE-674](https://metasolutions.atlassian.net/browse/ENTRYSTORE-674) Add support for RSS as response format for Solr queries

[ENTRYSTORE-711](https://metasolutions.atlassian.net/browse/ENTRYSTORE-711) Upgrade to Java 17

[ENTRYSTORE-712](https://metasolutions.atlassian.net/browse/ENTRYSTORE-712) Reimplement support for JSON-LD

[ENTRYSTORE-722](https://metasolutions.atlassian.net/browse/ENTRYSTORE-722) Serialize Strings according to RDF 1.0 without explicit data types

### Task

[ENTRYSTORE-715](https://metasolutions.atlassian.net/browse/ENTRYSTORE-715) Spring Boot POC
[ENTRYSTORE-716](https://metasolutions.atlassian.net/browse/ENTRYSTORE-716) Remake EntryStore to a Spring Boot App, removing all Jetty configuration, filters, Jetty configurations etc \(temporarily\)

### New Feature

[ENTRYSTORE-318](https://metasolutions.atlassian.net/browse/ENTRYSTORE-318) Enhancement using Apache Stanbol

[ENTRYSTORE-391](https://metasolutions.atlassian.net/browse/ENTRYSTORE-391) Add support for KiWi backend

[ENTRYSTORE-713](https://metasolutions.atlassian.net/browse/ENTRYSTORE-713) Support automatic migration of native store

### Bug

[ENTRYSTORE-714](https://metasolutions.atlassian.net/browse/ENTRYSTORE-714) RDF writers require startRDF\(\) before namespace handling

[ENTRYSTORE-723](https://metasolutions.atlassian.net/browse/ENTRYSTORE-723) Empty language properties in RDF/JSON requests break deserialization

[ENTRYSTORE-726](https://metasolutions.atlassian.net/browse/ENTRYSTORE-726) Profile field in Solr should be detected based on metadata URI in the entry graph

## Version 4.13 (2022-09-20)

### Bug

[ENTRYSTORE-698](https://metasolutions.atlassian.net/browse/ENTRYSTORE-698) Blocklist for proxy is ineffective

[ENTRYSTORE-696](https://metasolutions.atlassian.net/browse/ENTRYSTORE-696) Insufficient access control for \_principals

[ENTRYSTORE-684](https://metasolutions.atlassian.net/browse/ENTRYSTORE-684) Avoid XXE in validating XML parser in Echo resource

### Improvement

[ENTRYSTORE-710](https://metasolutions.atlassian.net/browse/ENTRYSTORE-710) Improve detection of need to reindex

[ENTRYSTORE-709](https://metasolutions.atlassian.net/browse/ENTRYSTORE-709) Invalidate auth token upon password change

[ENTRYSTORE-705](https://metasolutions.atlassian.net/browse/ENTRYSTORE-705) Remove format conversion functionality from Proxy resource

[ENTRYSTORE-704](https://metasolutions.atlassian.net/browse/ENTRYSTORE-704) Remove validation functionality from Proxy resource

[ENTRYSTORE-703](https://metasolutions.atlassian.net/browse/ENTRYSTORE-703) Perform Solr reindex also when Solr index version has changed

[ENTRYSTORE-702](https://metasolutions.atlassian.net/browse/ENTRYSTORE-702) Perform validation of client supplied entry IDs

[ENTRYSTORE-701](https://metasolutions.atlassian.net/browse/ENTRYSTORE-701) Add support for authenticated Solr connections

[ENTRYSTORE-700](https://metasolutions.atlassian.net/browse/ENTRYSTORE-700) SignupWhitelistResource discloses sensitive information

[ENTRYSTORE-699](https://metasolutions.atlassian.net/browse/ENTRYSTORE-699) Remove possibility to trigger context reindexing using GET request

[ENTRYSTORE-695](https://metasolutions.atlassian.net/browse/ENTRYSTORE-695) Prevent SSRF and XSS using Proxy resource

[ENTRYSTORE-694](https://metasolutions.atlassian.net/browse/ENTRYSTORE-694) Do not set default password for admin

[ENTRYSTORE-689](https://metasolutions.atlassian.net/browse/ENTRYSTORE-689) CAS: take redirection locations from configuration instead of URL

[ENTRYSTORE-686](https://metasolutions.atlassian.net/browse/ENTRYSTORE-686) Require authenticated user for use of Echo resource

[ENTRYSTORE-685](https://metasolutions.atlassian.net/browse/ENTRYSTORE-685) Remove validation functionality from Echo resource

[ENTRYSTORE-683](https://metasolutions.atlassian.net/browse/ENTRYSTORE-683) Alias not included in user information when fetched as part of group

[ENTRYSTORE-682](https://metasolutions.atlassian.net/browse/ENTRYSTORE-682) Make profile in entryinfo searchable via solr

[ENTRYSTORE-673](https://metasolutions.atlassian.net/browse/ENTRYSTORE-673) Add filters and tokenizer to text\_sort\_\*

### New Feature

[ENTRYSTORE-678](https://metasolutions.atlassian.net/browse/ENTRYSTORE-678) Make alias / name searchable in Solr

### Task

[ENTRYSTORE-708](https://metasolutions.atlassian.net/browse/ENTRYSTORE-708) Bump versions of dependencies due to security issues

## Version 4.12 (2021-12-11)

### Bug

[ENTRYSTORE-672](https://metasolutions.atlassian.net/browse/ENTRYSTORE-672) Custom parser config in GraphUtil is not thread safe

### Improvement

[ENTRYSTORE-679](https://metasolutions.atlassian.net/browse/ENTRYSTORE-679) Upgrade Log4j to 2.15.0 or newer

[ENTRYSTORE-677](https://metasolutions.atlassian.net/browse/ENTRYSTORE-677) Parameterize traversal depth and make max traversal depth configurable

[ENTRYSTORE-675](https://metasolutions.atlassian.net/browse/ENTRYSTORE-675) Add configurable size limit for some Solr fields

[ENTRYSTORE-665](https://metasolutions.atlassian.net/browse/ENTRYSTORE-665) Make it configurable to include other contexts in related property index

## Version 4.11 (2021-06-15)

### Bug

[ENTRYSTORE-671](https://metasolutions.atlassian.net/browse/ENTRYSTORE-671) Logout resource does not provide correct settings on removal cookie

### Improvement

[ENTRYSTORE-667](https://metasolutions.atlassian.net/browse/ENTRYSTORE-667) Replace deprecated Trie\* based numeric fields in Solr

[ENTRYSTORE-666](https://metasolutions.atlassian.net/browse/ENTRYSTORE-666) Optimize classpath and lib structure for standalone distributions

[ENTRYSTORE-662](https://metasolutions.atlassian.net/browse/ENTRYSTORE-662) Remove Edge NGram from query section at text\_ngram field

[ENTRYSTORE-661](https://metasolutions.atlassian.net/browse/ENTRYSTORE-661) Add additional data types to be processed as Date and Integer in Solr index

[ENTRYSTORE-659](https://metasolutions.atlassian.net/browse/ENTRYSTORE-659) Allow more flexible backup configuration

[ENTRYSTORE-628](https://metasolutions.atlassian.net/browse/ENTRYSTORE-628) Add Cache-Control header to all requests that are of relevance for caching in the reverse proxy

[ENTRYSTORE-591](https://metasolutions.atlassian.net/browse/ENTRYSTORE-591) Respond with HTTP 4xx status code if a request is made with an invalid authentication token

### New Feature

[ENTRYSTORE-668](https://metasolutions.atlassian.net/browse/ENTRYSTORE-668) Provide RDF validation resource

[ENTRYSTORE-658](https://metasolutions.atlassian.net/browse/ENTRYSTORE-658) Clear Solr folder upon version upgrade

[ENTRYSTORE-650](https://metasolutions.atlassian.net/browse/ENTRYSTORE-650) Support loading of Solr schema.xml from URL

[ENTRYSTORE-541](https://metasolutions.atlassian.net/browse/ENTRYSTORE-541) Add configuration setting to disable login for admin user

### Task

[ENTRYSTORE-669](https://metasolutions.atlassian.net/browse/ENTRYSTORE-669) Upgrade Solr to 8.8

## Version 4.10 (2021-02-17)

### Bug

[ENTRYSTORE-655](https://metasolutions.atlassian.net/browse/ENTRYSTORE-655) Configuration is not loaded as UTF-8

[ENTRYSTORE-654](https://metasolutions.atlassian.net/browse/ENTRYSTORE-654) Race condition for Solr indexing when removing contexts

[ENTRYSTORE-645](https://metasolutions.atlassian.net/browse/ENTRYSTORE-645) URI collections are not correctly serialized into String values when indexed

[ENTRYSTORE-639](https://metasolutions.atlassian.net/browse/ENTRYSTORE-639) Do not write authentication tokens in logger

[ENTRYSTORE-638](https://metasolutions.atlassian.net/browse/ENTRYSTORE-638) SPARQL resource should accept application/xml instead of application/rdf\+xml in HTTP header

[ENTRYSTORE-634](https://metasolutions.atlassian.net/browse/ENTRYSTORE-634) Disallow proxy requests to localhost, \*.local and plain IP addresses

[ENTRYSTORE-633](https://metasolutions.atlassian.net/browse/ENTRYSTORE-633) Name resource can be accessed by \_guest

[ENTRYSTORE-623](https://metasolutions.atlassian.net/browse/ENTRYSTORE-623) Accept-Language decimal number is incorrectly formatted when using certain locales

[ENTRYSTORE-617](https://metasolutions.atlassian.net/browse/ENTRYSTORE-617) An invalid RDF graph \(RDFJSON\) should not trigger an HTTP 500

[ENTRYSTORE-614](https://metasolutions.atlassian.net/browse/ENTRYSTORE-614) Logout resource sets the wrong path in cookie which creates a second cookie instead of removing the already set one

[ENTRYSTORE-613](https://metasolutions.atlassian.net/browse/ENTRYSTORE-613) CORS filter is not applied when invalid credentials are sent

[ENTRYSTORE-607](https://metasolutions.atlassian.net/browse/ENTRYSTORE-607) Admin password override may cause Internal Server Error if password violates rules

[ENTRYSTORE-556](https://metasolutions.atlassian.net/browse/ENTRYSTORE-556) Solr "results" field in response body may expose information to unauthorized users

### Improvement

[ENTRYSTORE-653](https://metasolutions.atlassian.net/browse/ENTRYSTORE-653) Solr: make EdgeNGram on query default for text\_ngram type

[ENTRYSTORE-652](https://metasolutions.atlassian.net/browse/ENTRYSTORE-652) Solr: change "description" field to text\_ngram

[ENTRYSTORE-647](https://metasolutions.atlassian.net/browse/ENTRYSTORE-647) Consolidate Log4j versions

[ENTRYSTORE-644](https://metasolutions.atlassian.net/browse/ENTRYSTORE-644) Change order of parameters for standalone to simplify config handling

[ENTRYSTORE-643](https://metasolutions.atlassian.net/browse/ENTRYSTORE-643) Add support for Jetty connector

[ENTRYSTORE-642](https://metasolutions.atlassian.net/browse/ENTRYSTORE-642) Add possibility to configure multi-threading when using non-Servlet connector

[ENTRYSTORE-641](https://metasolutions.atlassian.net/browse/ENTRYSTORE-641) Add query analyzer for ngram fields

[ENTRYSTORE-640](https://metasolutions.atlassian.net/browse/ENTRYSTORE-640) Enable loading of email templates from URLs

[ENTRYSTORE-637](https://metasolutions.atlassian.net/browse/ENTRYSTORE-637) Avoid CRLF injection for logs

[ENTRYSTORE-636](https://metasolutions.atlassian.net/browse/ENTRYSTORE-636) Do not allow SSL in CAS Login Resource

[ENTRYSTORE-635](https://metasolutions.atlassian.net/browse/ENTRYSTORE-635) Do not store password in memory during sign-up

[ENTRYSTORE-632](https://metasolutions.atlassian.net/browse/ENTRYSTORE-632) Allow to load configuration from URL

[ENTRYSTORE-631](https://metasolutions.atlassian.net/browse/ENTRYSTORE-631) Include Solr health check in status resource

[ENTRYSTORE-629](https://metasolutions.atlassian.net/browse/ENTRYSTORE-629) Remove a user's auth tokens upon successful password reset

[ENTRYSTORE-626](https://metasolutions.atlassian.net/browse/ENTRYSTORE-626) Periodically reload SAML metadata to avoid expired certificates

[ENTRYSTORE-625](https://metasolutions.atlassian.net/browse/ENTRYSTORE-625) Add retry for failed SMTP requests

[ENTRYSTORE-624](https://metasolutions.atlassian.net/browse/ENTRYSTORE-624) Add HTTPS to Client constructor in Proxy Resource

[ENTRYSTORE-620](https://metasolutions.atlassian.net/browse/ENTRYSTORE-620) Change submit request for Solr reindexing from GET to POST

[ENTRYSTORE-619](https://metasolutions.atlassian.net/browse/ENTRYSTORE-619) Add support for background reindexing of Solr index

[ENTRYSTORE-618](https://metasolutions.atlassian.net/browse/ENTRYSTORE-618) ModificationLockOutFilter is too restrictive

[ENTRYSTORE-615](https://metasolutions.atlassian.net/browse/ENTRYSTORE-615) Set file extension that matches the requested RDF format

[ENTRYSTORE-610](https://metasolutions.atlassian.net/browse/ENTRYSTORE-610) Upgrade stack to OpenJDK 11

[ENTRYSTORE-609](https://metasolutions.atlassian.net/browse/ENTRYSTORE-609) Add Docker/container specific settings to standalone configuration

[ENTRYSTORE-606](https://metasolutions.atlassian.net/browse/ENTRYSTORE-606) Allow owners of contexts to trigger re-index

[ENTRYSTORE-586](https://metasolutions.atlassian.net/browse/ENTRYSTORE-586) Replace FileInputStream and FileOutputStream with methods in Files class

[ENTRYSTORE-575](https://metasolutions.atlassian.net/browse/ENTRYSTORE-575) Send back entry information or just modification date on requests that modify an individual entry

[ENTRYSTORE-557](https://metasolutions.atlassian.net/browse/ENTRYSTORE-557) Automatically reindex context in Solr when its ACL has changed

[ENTRYSTORE-515](https://metasolutions.atlassian.net/browse/ENTRYSTORE-515) Harmonize configuration values on/off and true/false

[ENTRYSTORE-492](https://metasolutions.atlassian.net/browse/ENTRYSTORE-492) Optimize Solr reindex on startup to be able to run in own thread

[ENTRYSTORE-454](https://metasolutions.atlassian.net/browse/ENTRYSTORE-454) Improve handling of command line parameters

[ENTRYSTORE-407](https://metasolutions.atlassian.net/browse/ENTRYSTORE-407) Ensure that Solr field names only contain alphanumeric characters and underscore

[ENTRYSTORE-369](https://metasolutions.atlassian.net/browse/ENTRYSTORE-369) Upgrade to a recent Solr version

### New Feature

[ENTRYSTORE-649](https://metasolutions.atlassian.net/browse/ENTRYSTORE-649) Make Restlet use same logging framework as rest of application

[ENTRYSTORE-648](https://metasolutions.atlassian.net/browse/ENTRYSTORE-648) Allow to set log level via REST API

[ENTRYSTORE-646](https://metasolutions.atlassian.net/browse/ENTRYSTORE-646) Add command line parameter to provide settings for server connectors

[ENTRYSTORE-612](https://metasolutions.atlassian.net/browse/ENTRYSTORE-612) Add possibility to configure settings of authentication cookie

[ENTRYSTORE-611](https://metasolutions.atlassian.net/browse/ENTRYSTORE-611) Add Cache-Control header in response to authenticated requests

[ENTRYSTORE-608](https://metasolutions.atlassian.net/browse/ENTRYSTORE-608) Add possiblity to sort after relevance/boost

[ENTRYSTORE-577](https://metasolutions.atlassian.net/browse/ENTRYSTORE-577) Allow searching for users by username

[ENTRYSTORE-525](https://metasolutions.atlassian.net/browse/ENTRYSTORE-525) Add support for SAML 2.0

[ENTRYSTORE-484](https://metasolutions.atlassian.net/browse/ENTRYSTORE-484) Modification date as response to all PUT requests

### Task

[ENTRYSTORE-651](https://metasolutions.atlassian.net/browse/ENTRYSTORE-651) Update NOTICE.txt

[ENTRYSTORE-622](https://metasolutions.atlassian.net/browse/ENTRYSTORE-622) Remove support for IEEE LOM and all dependencies

[ENTRYSTORE-621](https://metasolutions.atlassian.net/browse/ENTRYSTORE-621) Remove deprecated support for OpenID

[ENTRYSTORE-604](https://metasolutions.atlassian.net/browse/ENTRYSTORE-604) Make sure there are no duplicate and conflicting dependencies

[ENTRYSTORE-593](https://metasolutions.atlassian.net/browse/ENTRYSTORE-593) Evaluate performance of Solr document batch processing

[ENTRYSTORE-574](https://metasolutions.atlassian.net/browse/ENTRYSTORE-574) Investigate whether support for Elasticsearch \(in addition to or replacing Solr\) is feasible

[ENTRYSTORE-534](https://metasolutions.atlassian.net/browse/ENTRYSTORE-534) Investigate why Solr is slow in large instances

[ENTRYSTORE-528](https://metasolutions.atlassian.net/browse/ENTRYSTORE-528) Document how to setup EntryStore with Google as Identity Provider

[ENTRYSTORE-527](https://metasolutions.atlassian.net/browse/ENTRYSTORE-527) Prepare for breaking changes in Java 10

## Version 4.9 (2019-12-04)

### Bug

[ENTRYSTORE-623](https://metasolutions.atlassian.net/browse/ENTRYSTORE-623) Accept-Language decimal number is incorrectly formatted when using certain locales

[ENTRYSTORE-602](https://metasolutions.atlassian.net/browse/ENTRYSTORE-602) Too big Solr delete batches cause crash of document submitter thread

[ENTRYSTORE-601](https://metasolutions.atlassian.net/browse/ENTRYSTORE-601) Password reset mechanism uses weak method for creation of confirmation token

[ENTRYSTORE-594](https://metasolutions.atlassian.net/browse/ENTRYSTORE-594) Avoid XXE in XML parsers

[ENTRYSTORE-582](https://metasolutions.atlassian.net/browse/ENTRYSTORE-582) Lookup fails when resource URI is changed

[ENTRYSTORE-580](https://metasolutions.atlassian.net/browse/ENTRYSTORE-580) Invalid link to return to EntryScape in auth/signup page

### Improvement

[ENTRYSTORE-599](https://metasolutions.atlassian.net/browse/ENTRYSTORE-599) Escape user provided strings that are sent in emails

[ENTRYSTORE-598](https://metasolutions.atlassian.net/browse/ENTRYSTORE-598) Remove support for multi-part form uploads and textarea responses

[ENTRYSTORE-597](https://metasolutions.atlassian.net/browse/ENTRYSTORE-597) Add configuration for Content-Disposition: inline for access to resources

[ENTRYSTORE-596](https://metasolutions.atlassian.net/browse/ENTRYSTORE-596) Remove support for "method" URL parameter to avoid CSRF

[ENTRYSTORE-585](https://metasolutions.atlassian.net/browse/ENTRYSTORE-585) Wrong HTTP status code for incorrect user/pw combination and disabled user

[ENTRYSTORE-584](https://metasolutions.atlassian.net/browse/ENTRYSTORE-584) Return HTTP 400 when client sends POST to resource when PUT should be used

[ENTRYSTORE-578](https://metasolutions.atlassian.net/browse/ENTRYSTORE-578) Add URL of instance to email footer and SMTP headers

### New Feature

[ENTRYSTORE-603](https://metasolutions.atlassian.net/browse/ENTRYSTORE-603) Allow Solr query to return list of matching entry URIs

[ENTRYSTORE-589](https://metasolutions.atlassian.net/browse/ENTRYSTORE-589) Support Solr indexing of text fields using Unicode Collation

## Version 4.8 (2019-04-03)

### Bug

[ENTRYSTORE-564](https://metasolutions.atlassian.net/browse/ENTRYSTORE-564) Server returns HTTP 500 when sending bogus request to CookieLoginResource

### Improvement

[ENTRYSTORE-568](https://metasolutions.atlassian.net/browse/ENTRYSTORE-568) Add randomization of start time to backup execution

[ENTRYSTORE-567](https://metasolutions.atlassian.net/browse/ENTRYSTORE-567) Add possibility to configure "Reply-to" field for sent emails

[ENTRYSTORE-559](https://metasolutions.atlassian.net/browse/ENTRYSTORE-559) Add support for additional RDF formats for backup

[ENTRYSTORE-517](https://metasolutions.atlassian.net/browse/ENTRYSTORE-517) Move generic SMTP settings from auth config to smtp config

### New Feature

[ENTRYSTORE-566](https://metasolutions.atlassian.net/browse/ENTRYSTORE-566) Add proxy resource for contexts \(in addition to global proxy\)

[ENTRYSTORE-424](https://metasolutions.atlassian.net/browse/ENTRYSTORE-424) Collect and expose statistics about resource access

## Version 4.7 (2019-02-14)

### Bug

[ENTRYSTORE-555](https://metasolutions.atlassian.net/browse/ENTRYSTORE-555) Restrict access to sensitive information in StatusResource to members of admin group

[ENTRYSTORE-554](https://metasolutions.atlassian.net/browse/ENTRYSTORE-554) Posting RDF content that cannot be properly deserialized causes an emtpy graph

[ENTRYSTORE-547](https://metasolutions.atlassian.net/browse/ENTRYSTORE-547) Delete contexts only via their entry URI

[ENTRYSTORE-543](https://metasolutions.atlassian.net/browse/ENTRYSTORE-543) Do not publicly expose information about principals

[ENTRYSTORE-542](https://metasolutions.atlassian.net/browse/ENTRYSTORE-542) Catch errors due to malformed password syntax

[ENTRYSTORE-533](https://metasolutions.atlassian.net/browse/ENTRYSTORE-533) Solr search with limit 1 may not return any result despite the result count being greater than 0

[ENTRYSTORE-522](https://metasolutions.atlassian.net/browse/ENTRYSTORE-522) Users without metadata \(auto-provisioned\) don't show up in the admin UI

### Improvement

[ENTRYSTORE-550](https://metasolutions.atlassian.net/browse/ENTRYSTORE-550) Make Solr search limit configurable

[ENTRYSTORE-549](https://metasolutions.atlassian.net/browse/ENTRYSTORE-549) Add provenance repository to backup

[ENTRYSTORE-545](https://metasolutions.atlassian.net/browse/ENTRYSTORE-545) Optimize repository operations in PublicRepository

[ENTRYSTORE-539](https://metasolutions.atlassian.net/browse/ENTRYSTORE-539) Allow for configuration of auth cookie path

[ENTRYSTORE-538](https://metasolutions.atlassian.net/browse/ENTRYSTORE-538) Remove all fuzzy matches for object values from Solr index

[ENTRYSTORE-536](https://metasolutions.atlassian.net/browse/ENTRYSTORE-536) Add new response type to echo server

[ENTRYSTORE-535](https://metasolutions.atlassian.net/browse/ENTRYSTORE-535) Add filters to recursive API

[ENTRYSTORE-531](https://metasolutions.atlassian.net/browse/ENTRYSTORE-531) Introduce text index for literals in addition to ngram

[ENTRYSTORE-521](https://metasolutions.atlassian.net/browse/ENTRYSTORE-521) Change Solr field metadata.predicate.date to single value

[ENTRYSTORE-514](https://metasolutions.atlassian.net/browse/ENTRYSTORE-514) Limit the maximum password length

### New Feature

[ENTRYSTORE-530](https://metasolutions.atlassian.net/browse/ENTRYSTORE-530) Initial implementation of a related entity property index

[ENTRYSTORE-519](https://metasolutions.atlassian.net/browse/ENTRYSTORE-519) Provide information about heap och native memory consumption

[ENTRYSTORE-447](https://metasolutions.atlassian.net/browse/ENTRYSTORE-447) Provide download of CSV file containing all users

### Task

[ENTRYSTORE-537](https://metasolutions.atlassian.net/browse/ENTRYSTORE-537) Discuss and document the role of Solr in EntryStore

[ENTRYSTORE-518](https://metasolutions.atlassian.net/browse/ENTRYSTORE-518) Reevaluate the default ACL of user entries

[ENTRYSTORE-510](https://metasolutions.atlassian.net/browse/ENTRYSTORE-510) Remove Mysema Stat and PC-Axis support due to licensing issues

[ENTRYSTORE-478](https://metasolutions.atlassian.net/browse/ENTRYSTORE-478) Make sure all RepositoryResults are closed after use

## Version 4.6 (2018-07-06)

### Bug

[ENTRYSTORE-509](https://metasolutions.atlassian.net/browse/ENTRYSTORE-509) Manual configuration of SMTP port does not have any effect

[ENTRYSTORE-508](https://metasolutions.atlassian.net/browse/ENTRYSTORE-508) Configuration manager uses wrong encoding when loading properties file

[ENTRYSTORE-506](https://metasolutions.atlassian.net/browse/ENTRYSTORE-506) Add foaf:familyName to Solr index field "title"

[ENTRYSTORE-502](https://metasolutions.atlassian.net/browse/ENTRYSTORE-502) Changing a username \(as admin\) causes a login as the changed user

[ENTRYSTORE-500](https://metasolutions.atlassian.net/browse/ENTRYSTORE-500) Backup maintenance uses ArrayList, should use LinkedList

[ENTRYSTORE-499](https://metasolutions.atlassian.net/browse/ENTRYSTORE-499) Propagate AuthorizationException inside MetadataImpl.setGraph\(\) upwards

[ENTRYSTORE-496](https://metasolutions.atlassian.net/browse/ENTRYSTORE-496) Response after creation of entry is not valid JSON if entryid is not an integer

[ENTRYSTORE-487](https://metasolutions.atlassian.net/browse/ENTRYSTORE-487) Missing search result when ACL and limit

[ENTRYSTORE-482](https://metasolutions.atlassian.net/browse/ENTRYSTORE-482) skos:prefLabel should appear before skos:altLabel in title in solr index

[ENTRYSTORE-428](https://metasolutions.atlassian.net/browse/ENTRYSTORE-428) Possible performance issue when creating many contexts and groups

### Improvement

[ENTRYSTORE-513](https://metasolutions.atlassian.net/browse/ENTRYSTORE-513) Add startup date to status resource

[ENTRYSTORE-512](https://metasolutions.atlassian.net/browse/ENTRYSTORE-512) Add auth token count to status resource

[ENTRYSTORE-511](https://metasolutions.atlassian.net/browse/ENTRYSTORE-511) Disable automatic creation of home contexts on user-initiated signups

[ENTRYSTORE-504](https://metasolutions.atlassian.net/browse/ENTRYSTORE-504) Return another status code when a user that is blocked sign in with the right credentials

[ENTRYSTORE-503](https://metasolutions.atlassian.net/browse/ENTRYSTORE-503) Sorting of titles should not prefer upper case over lower case

[ENTRYSTORE-501](https://metasolutions.atlassian.net/browse/ENTRYSTORE-501) Do not send email on password reset if user does not exist

[ENTRYSTORE-490](https://metasolutions.atlassian.net/browse/ENTRYSTORE-490) Improve failure message when using old token on signup

[ENTRYSTORE-486](https://metasolutions.atlassian.net/browse/ENTRYSTORE-486) Encode email subject if they contain non-ASCII characters

[ENTRYSTORE-480](https://metasolutions.atlassian.net/browse/ENTRYSTORE-480) Optimize entrystore-tools to make use of transactions and streaming results

[ENTRYSTORE-470](https://metasolutions.atlassian.net/browse/ENTRYSTORE-470) Introduce possibility to disable accounts

[ENTRYSTORE-464](https://metasolutions.atlassian.net/browse/ENTRYSTORE-464) Send acknowledgement email after successful password reset

[ENTRYSTORE-452](https://metasolutions.atlassian.net/browse/ENTRYSTORE-452) Add whitelisting to proxy for requests to certain domains by anonymous users

### New Feature

[ENTRYSTORE-507](https://metasolutions.atlassian.net/browse/ENTRYSTORE-507) Add configurable auto-provisioning for SSO

[ENTRYSTORE-497](https://metasolutions.atlassian.net/browse/ENTRYSTORE-497) Provide resource to fetch index information

[ENTRYSTORE-495](https://metasolutions.atlassian.net/browse/ENTRYSTORE-495) Add validation functionality to Echo and Proxy resources

### Task

[ENTRYSTORE-477](https://metasolutions.atlassian.net/browse/ENTRYSTORE-477) Compile list of dependencies and their licenses

[ENTRYSTORE-353](https://metasolutions.atlassian.net/browse/ENTRYSTORE-353) Check whether Solr usage is optimized

## Version 4.5 (2017-12-21)

### Bug

[ENTRYSTORE-258](https://metasolutions.atlassian.net/browse/ENTRYSTORE-258) Creating references between portfolios does not work with copy/paste

### Improvement

[ENTRYSTORE-476](https://metasolutions.atlassian.net/browse/ENTRYSTORE-476) Make tracking of deleted entries optional

[ENTRYSTORE-475](https://metasolutions.atlassian.net/browse/ENTRYSTORE-475) Avoid sending hard commits to Solr

[ENTRYSTORE-474](https://metasolutions.atlassian.net/browse/ENTRYSTORE-474) Optimize handling of delete queries for Solr

[ENTRYSTORE-472](https://metasolutions.atlassian.net/browse/ENTRYSTORE-472) Change log level for Solr errors caused by malformed user input

[ENTRYSTORE-469](https://metasolutions.atlassian.net/browse/ENTRYSTORE-469) Add configuration option to apply Ngram in addition to whitespace for generic literal index

[ENTRYSTORE-468](https://metasolutions.atlassian.net/browse/ENTRYSTORE-468) User Solr date type for generic indexing of date literals

[ENTRYSTORE-467](https://metasolutions.atlassian.net/browse/ENTRYSTORE-467) Recursive calls should be possible across contexts

[ENTRYSTORE-460](https://metasolutions.atlassian.net/browse/ENTRYSTORE-460) Improve design of default email templates

[ENTRYSTORE-374](https://metasolutions.atlassian.net/browse/ENTRYSTORE-374) Externalize configuration of title- and tag-field in Solr index

[ENTRYSTORE-304](https://metasolutions.atlassian.net/browse/ENTRYSTORE-304) Generic SSO support for authentication and account creation

[ENTRYSTORE-250](https://metasolutions.atlassian.net/browse/ENTRYSTORE-250) Fix harvesting of "unclean" OAI targets

### New Feature

[ENTRYSTORE-473](https://metasolutions.atlassian.net/browse/ENTRYSTORE-473) Add setting to disallow users to login using username and password

[ENTRYSTORE-471](https://metasolutions.atlassian.net/browse/ENTRYSTORE-471) EchoResource needs provide error handling inside textarea

[ENTRYSTORE-463](https://metasolutions.atlassian.net/browse/ENTRYSTORE-463) Provide flag to apply SPARQL construct query on metadata access

[ENTRYSTORE-461](https://metasolutions.atlassian.net/browse/ENTRYSTORE-461) Provide way for logged in users to make non-authenticated requests

[ENTRYSTORE-280](https://metasolutions.atlassian.net/browse/ENTRYSTORE-280) Add support for basic faceted search to Solr

[ENTRYSTORE-81](https://metasolutions.atlassian.net/browse/ENTRYSTORE-81) Automated checking of links

### Task

[ENTRYSTORE-324](https://metasolutions.atlassian.net/browse/ENTRYSTORE-324) Investigate integration with CKAN

## Version 4.4 (2017-06-05)

### Bug

[ENTRYSTORE-455](https://metasolutions.atlassian.net/browse/ENTRYSTORE-455) Slow response when relation graph is big

[ENTRYSTORE-451](https://metasolutions.atlassian.net/browse/ENTRYSTORE-451) 500 after DELETE with curl

[ENTRYSTORE-450](https://metasolutions.atlassian.net/browse/ENTRYSTORE-450) Solr search on title should treat multiple words as AND not OR

[ENTRYSTORE-449](https://metasolutions.atlassian.net/browse/ENTRYSTORE-449) CORS support does not work anymore with recent Restlet versions

[ENTRYSTORE-440](https://metasolutions.atlassian.net/browse/ENTRYSTORE-440) Modification date for metadata request with recursive is wrong

[ENTRYSTORE-439](https://metasolutions.atlassian.net/browse/ENTRYSTORE-439) Problem accessing auth/user resource after username change

[ENTRYSTORE-139](https://metasolutions.atlassian.net/browse/ENTRYSTORE-139) ContextImpl.remove\(URI entryURI\) does not remove all triples from context

### Improvement

[ENTRYSTORE-459](https://metasolutions.atlassian.net/browse/ENTRYSTORE-459) Remove backup management from REST API

[ENTRYSTORE-458](https://metasolutions.atlassian.net/browse/ENTRYSTORE-458) Remove application specific system entries

[ENTRYSTORE-457](https://metasolutions.atlassian.net/browse/ENTRYSTORE-457) Add information about versioning capabilities to status resource

[ENTRYSTORE-456](https://metasolutions.atlassian.net/browse/ENTRYSTORE-456) Add global lookups to LookupResource

[ENTRYSTORE-444](https://metasolutions.atlassian.net/browse/ENTRYSTORE-444) Remove the requirement of providing at least 3 characters for searches

[ENTRYSTORE-411](https://metasolutions.atlassian.net/browse/ENTRYSTORE-411) Add support for changing datasets in RowStore

[ENTRYSTORE-406](https://metasolutions.atlassian.net/browse/ENTRYSTORE-406) Upgrade to Java 8

[ENTRYSTORE-405](https://metasolutions.atlassian.net/browse/ENTRYSTORE-405) JSONP filter should give application/javascript instead of application/json

[ENTRYSTORE-322](https://metasolutions.atlassian.net/browse/ENTRYSTORE-322) Clean up RepositoryManagerImpl and EntryStoreApplication

[ENTRYSTORE-68](https://metasolutions.atlassian.net/browse/ENTRYSTORE-68) Relevant HTTP status codes for requests to the REST layer

### New Feature

[ENTRYSTORE-443](https://metasolutions.atlassian.net/browse/ENTRYSTORE-443) Add support for providing path to configuration file via environment variable

[ENTRYSTORE-442](https://metasolutions.atlassian.net/browse/ENTRYSTORE-442) Add support for setting and changing a RowStore dataset's alias

[ENTRYSTORE-421](https://metasolutions.atlassian.net/browse/ENTRYSTORE-421) Disallow context names that conflict with REST resources

[ENTRYSTORE-416](https://metasolutions.atlassian.net/browse/ENTRYSTORE-416) Return HTTP Accept-Language header as language array in user object

[ENTRYSTORE-366](https://metasolutions.atlassian.net/browse/ENTRYSTORE-366) Investigate sanity of ProxyResource

[ENTRYSTORE-360](https://metasolutions.atlassian.net/browse/ENTRYSTORE-360) Provide easy access to properties in entry information

[ENTRYSTORE-341](https://metasolutions.atlassian.net/browse/ENTRYSTORE-341) Allow initial configuration via REST

[ENTRYSTORE-272](https://metasolutions.atlassian.net/browse/ENTRYSTORE-272) Automatically specify the correct page of a list so that a given child entry is included on that page

[ENTRYSTORE-205](https://metasolutions.atlassian.net/browse/ENTRYSTORE-205) Add versioning support for metadata

[ENTRYSTORE-47](https://metasolutions.atlassian.net/browse/ENTRYSTORE-47) Support for modifying groups in ResourceResource

### Task

[ENTRYSTORE-349](https://metasolutions.atlassian.net/browse/ENTRYSTORE-349) Deploy to Maven Central

[ENTRYSTORE-335](https://metasolutions.atlassian.net/browse/ENTRYSTORE-335) Investigate whether Docker can be supported easily

[ENTRYSTORE-270](https://metasolutions.atlassian.net/browse/ENTRYSTORE-270) Check the code for DELETE on a context

## Version 4.3 (2016-08-26)

### Bug

[ENTRYSTORE-426](https://metasolutions.atlassian.net/browse/ENTRYSTORE-426) Graph and Pipeline has \_newId still in RDF

[ENTRYSTORE-425](https://metasolutions.atlassian.net/browse/ENTRYSTORE-425) Pipeline resources not accessible

[ENTRYSTORE-417](https://metasolutions.atlassian.net/browse/ENTRYSTORE-417) Allow removal of context and group names

[ENTRYSTORE-415](https://metasolutions.atlassian.net/browse/ENTRYSTORE-415) Entries in context not removed from Solr index  when context is removed

[ENTRYSTORE-402](https://metasolutions.atlassian.net/browse/ENTRYSTORE-402) Sign-up does not allow new TLDs

[ENTRYSTORE-401](https://metasolutions.atlassian.net/browse/ENTRYSTORE-401) Deadlock due to combination of synchronized on methods and repository.

[ENTRYSTORE-394](https://metasolutions.atlassian.net/browse/ENTRYSTORE-394) Concurrent modification exception in token cache

[ENTRYSTORE-393](https://metasolutions.atlassian.net/browse/ENTRYSTORE-393) Review entrystore-core-impl and check correct repository synchronization

[ENTRYSTORE-342](https://metasolutions.atlassian.net/browse/ENTRYSTORE-342) Check for concurrency problems in Impl-classes

### Improvement

[ENTRYSTORE-434](https://metasolutions.atlassian.net/browse/ENTRYSTORE-434) Initialize repository with test data only when explicitly configured

[ENTRYSTORE-433](https://metasolutions.atlassian.net/browse/ENTRYSTORE-433) Respond with HTTP status 504 on timed out proxy requests

[ENTRYSTORE-432](https://metasolutions.atlassian.net/browse/ENTRYSTORE-432) Force Solr reindexing if backend is memory store

[ENTRYSTORE-430](https://metasolutions.atlassian.net/browse/ENTRYSTORE-430) Restrict creation of PipelineResults to Pipeline execution

[ENTRYSTORE-429](https://metasolutions.atlassian.net/browse/ENTRYSTORE-429) Introduce new graph type PipelineResult

[ENTRYSTORE-423](https://metasolutions.atlassian.net/browse/ENTRYSTORE-423) Add possibility to configure a maximum file size for resources

[ENTRYSTORE-422](https://metasolutions.atlassian.net/browse/ENTRYSTORE-422) Make sure large file uploads are not loaded into memory

[ENTRYSTORE-419](https://metasolutions.atlassian.net/browse/ENTRYSTORE-419) Cookies are not removed on logout

[ENTRYSTORE-414](https://metasolutions.atlassian.net/browse/ENTRYSTORE-414) Provide status in JSON also to unauthenticated users

[ENTRYSTORE-413](https://metasolutions.atlassian.net/browse/ENTRYSTORE-413) Add version number to status resource

[ENTRYSTORE-412](https://metasolutions.atlassian.net/browse/ENTRYSTORE-412) Allow traversal of metadata graphs to cross context borders

[ENTRYSTORE-404](https://metasolutions.atlassian.net/browse/ENTRYSTORE-404) Improve email templates

[ENTRYSTORE-403](https://metasolutions.atlassian.net/browse/ENTRYSTORE-403) Server-generated form for sign-up uses old reCaptcha

[ENTRYSTORE-398](https://metasolutions.atlassian.net/browse/ENTRYSTORE-398) Improve indexing of literals used for categorization

[ENTRYSTORE-397](https://metasolutions.atlassian.net/browse/ENTRYSTORE-397) Check that volatile variables are used where necessary

[ENTRYSTORE-396](https://metasolutions.atlassian.net/browse/ENTRYSTORE-396) Logging level ERROR should not be used for client errors

[ENTRYSTORE-395](https://metasolutions.atlassian.net/browse/ENTRYSTORE-395) User names should be handled case insensitively

[ENTRYSTORE-382](https://metasolutions.atlassian.net/browse/ENTRYSTORE-382) Provide JSON in auth/cookie as in auth/user

### New Feature

[ENTRYSTORE-438](https://metasolutions.atlassian.net/browse/ENTRYSTORE-438) Allow sorting based on triples with integer values

[ENTRYSTORE-437](https://metasolutions.atlassian.net/browse/ENTRYSTORE-437) Include rdf:type expressed in Entry-information in Solr index

[ENTRYSTORE-431](https://metasolutions.atlassian.net/browse/ENTRYSTORE-431) Introduce a status field in entryinfo

[ENTRYSTORE-427](https://metasolutions.atlassian.net/browse/ENTRYSTORE-427) Introduce empty transform for pipelines

[ENTRYSTORE-420](https://metasolutions.atlassian.net/browse/ENTRYSTORE-420) Allow uploading a file and get the result back without creating an entry

[ENTRYSTORE-410](https://metasolutions.atlassian.net/browse/ENTRYSTORE-410) Provide way of storing arbitrary information on sign-up

[ENTRYSTORE-408](https://metasolutions.atlassian.net/browse/ENTRYSTORE-408) Add predicate-object tuples to Solr

[ENTRYSTORE-387](https://metasolutions.atlassian.net/browse/ENTRYSTORE-387) Make it possible to check if a username is in use or not

[ENTRYSTORE-386](https://metasolutions.atlassian.net/browse/ENTRYSTORE-386) List of entries in \_principals and \_contexts should require admin

[ENTRYSTORE-385](https://metasolutions.atlassian.net/browse/ENTRYSTORE-385) Support invites

[ENTRYSTORE-383](https://metasolutions.atlassian.net/browse/ENTRYSTORE-383) Make is possible to restrict account sign-up to specific domains

## Version 4.2 (2015-07-02)

### Bug

[ENTRYSTORE-334](https://metasolutions.atlassian.net/browse/ENTRYSTORE-334) Reenable unit tests for entrystore-core-impl

### Improvement

[ENTRYSTORE-381](https://metasolutions.atlassian.net/browse/ENTRYSTORE-381) Add date of session expiration to user info

[ENTRYSTORE-380](https://metasolutions.atlassian.net/browse/ENTRYSTORE-380) Append API version to version number

[ENTRYSTORE-375](https://metasolutions.atlassian.net/browse/ENTRYSTORE-375) Include object URIs in the Solr index

[ENTRYSTORE-368](https://metasolutions.atlassian.net/browse/ENTRYSTORE-368) Make signup and password reset work with reCaptcha 2.0

[ENTRYSTORE-363](https://metasolutions.atlassian.net/browse/ENTRYSTORE-363) REST API should support more RDF-formats for Graph resources

[ENTRYSTORE-362](https://metasolutions.atlassian.net/browse/ENTRYSTORE-362) Merge GraphType with ResourceType

[ENTRYSTORE-359](https://metasolutions.atlassian.net/browse/ENTRYSTORE-359) Rename "alias" to "name" in interfaces and REST API

[ENTRYSTORE-358](https://metasolutions.atlassian.net/browse/ENTRYSTORE-358) Rename "alias" to "name" in interfaces and REST API

[ENTRYSTORE-356](https://metasolutions.atlassian.net/browse/ENTRYSTORE-356) Update Restlet to 2.3 branch

[ENTRYSTORE-350](https://metasolutions.atlassian.net/browse/ENTRYSTORE-350) Add support for additional content types to JSONP support

[ENTRYSTORE-347](https://metasolutions.atlassian.net/browse/ENTRYSTORE-347) Create new package entrystore-rest-standalone

[ENTRYSTORE-345](https://metasolutions.atlassian.net/browse/ENTRYSTORE-345) Simplify JSON on PUT

[ENTRYSTORE-337](https://metasolutions.atlassian.net/browse/ENTRYSTORE-337) Default tunneling of PUT and DELETE through POST

[ENTRYSTORE-336](https://metasolutions.atlassian.net/browse/ENTRYSTORE-336) Cannot PUT cached-external-metadata

[ENTRYSTORE-333](https://metasolutions.atlassian.net/browse/ENTRYSTORE-333) Separate interfaces and implementations into different modules

[ENTRYSTORE-328](https://metasolutions.atlassian.net/browse/ENTRYSTORE-328) Use Sesame Rio for loading parsers and writers

[ENTRYSTORE-305](https://metasolutions.atlassian.net/browse/ENTRYSTORE-305) Check whether Solr search improves by using DisMax

[ENTRYSTORE-53](https://metasolutions.atlassian.net/browse/ENTRYSTORE-53) Make all resources symmetric

### New Feature

[ENTRYSTORE-379](https://metasolutions.atlassian.net/browse/ENTRYSTORE-379) Redirect from resource to metadata if entry is local and named

[ENTRYSTORE-378](https://metasolutions.atlassian.net/browse/ENTRYSTORE-378) Enable file download of metadata graphs

[ENTRYSTORE-376](https://metasolutions.atlassian.net/browse/ENTRYSTORE-376) Support traversal profiles for metadata graphs

[ENTRYSTORE-373](https://metasolutions.atlassian.net/browse/ENTRYSTORE-373) Enable custom server signature

[ENTRYSTORE-372](https://metasolutions.atlassian.net/browse/ENTRYSTORE-372) Support HTTP HEAD in EntryResource

[ENTRYSTORE-370](https://metasolutions.atlassian.net/browse/ENTRYSTORE-370) Optional possibility for non-admins to create group with linked context

[ENTRYSTORE-361](https://metasolutions.atlassian.net/browse/ENTRYSTORE-361) Add support for RDF to List-handling in ResourceResource

[ENTRYSTORE-340](https://metasolutions.atlassian.net/browse/ENTRYSTORE-340) Add OpenID links to HTML representations of login and signup resources

[ENTRYSTORE-339](https://metasolutions.atlassian.net/browse/ENTRYSTORE-339) HTML representation for login and logout resources

[ENTRYSTORE-338](https://metasolutions.atlassian.net/browse/ENTRYSTORE-338) Add support for Solr via HTTP

[ENTRYSTORE-329](https://metasolutions.atlassian.net/browse/ENTRYSTORE-329) Add support for user-initiated password-reset

[ENTRYSTORE-325](https://metasolutions.atlassian.net/browse/ENTRYSTORE-325) Add support for CORS

[ENTRYSTORE-321](https://metasolutions.atlassian.net/browse/ENTRYSTORE-321) Add support for Virtuoso as backend

[ENTRYSTORE-319](https://metasolutions.atlassian.net/browse/ENTRYSTORE-319) Add support for OWLIM as backend

[ENTRYSTORE-314](https://metasolutions.atlassian.net/browse/ENTRYSTORE-314) Add SPARQL endpoint for entries of ResourceType.Graph

[ENTRYSTORE-306](https://metasolutions.atlassian.net/browse/ENTRYSTORE-306) Add support for JSON-LD

[ENTRYSTORE-290](https://metasolutions.atlassian.net/browse/ENTRYSTORE-290) Add autocompletion to some Solr fields

[ENTRYSTORE-127](https://metasolutions.atlassian.net/browse/ENTRYSTORE-127) Make ACL information show up in the inverse relation cache for users and groups

### Task

[ENTRYSTORE-327](https://metasolutions.atlassian.net/browse/ENTRYSTORE-327) Optimize Maven repositories

[ENTRYSTORE-323](https://metasolutions.atlassian.net/browse/ENTRYSTORE-323) Clean up MetadataResource

[ENTRYSTORE-309](https://metasolutions.atlassian.net/browse/ENTRYSTORE-309) Prepare Individual Contributor License Agreement

[ENTRYSTORE-297](https://metasolutions.atlassian.net/browse/ENTRYSTORE-297) Migrate code repository from Subversion to Git

[ENTRYSTORE-289](https://metasolutions.atlassian.net/browse/ENTRYSTORE-289) Document API using Swagger

[ENTRYSTORE-42](https://metasolutions.atlassian.net/browse/ENTRYSTORE-42) Check all REST URI and their JSON input and output

## Version 4.1 (2014-02-12)

### Bug

[ENTRYSTORE-285](https://metasolutions.atlassian.net/browse/ENTRYSTORE-285) Authentication workaround for POST from forms is defect

[ENTRYSTORE-277](https://metasolutions.atlassian.net/browse/ENTRYSTORE-277) Inverse relational cache not updated after entry is removed

[ENTRYSTORE-267](https://metasolutions.atlassian.net/browse/ENTRYSTORE-267) MIME type is lost when uploading content from Confolio

[ENTRYSTORE-238](https://metasolutions.atlassian.net/browse/ENTRYSTORE-238) When a new User is created, the guest-user automatically gets read-rights to both MD and Resource

### Improvement

[ENTRYSTORE-332](https://metasolutions.atlassian.net/browse/ENTRYSTORE-332) Bump jsonld-java-sesame to 0.3

[ENTRYSTORE-311](https://metasolutions.atlassian.net/browse/ENTRYSTORE-311) Add support for HTTP and SPARQL repositories as backends

[ENTRYSTORE-310](https://metasolutions.atlassian.net/browse/ENTRYSTORE-310) Add backup configuration to entrystore.properties

[ENTRYSTORE-301](https://metasolutions.atlassian.net/browse/ENTRYSTORE-301) Add OpenID configuration to properties file

[ENTRYSTORE-300](https://metasolutions.atlassian.net/browse/ENTRYSTORE-300) Check whether shutdown hooks are correctly implemented

[ENTRYSTORE-298](https://metasolutions.atlassian.net/browse/ENTRYSTORE-298) Amount of results in a search does not take into account access rights

[ENTRYSTORE-296](https://metasolutions.atlassian.net/browse/ENTRYSTORE-296) Avoid checking credentials with every request

[ENTRYSTORE-294](https://metasolutions.atlassian.net/browse/ENTRYSTORE-294) Add possibility to request resources/metadata based on the resource URI

[ENTRYSTORE-291](https://metasolutions.atlassian.net/browse/ENTRYSTORE-291) Upgrade Solr to a version where instant updates are supported

[ENTRYSTORE-287](https://metasolutions.atlassian.net/browse/ENTRYSTORE-287) Show a nice message if access to resource is forbidden

[ENTRYSTORE-284](https://metasolutions.atlassian.net/browse/ENTRYSTORE-284) Reorganize maven build structure

[ENTRYSTORE-282](https://metasolutions.atlassian.net/browse/ENTRYSTORE-282) Store only hashed secrets

[ENTRYSTORE-210](https://metasolutions.atlassian.net/browse/ENTRYSTORE-210) Installation script for quick setup of EntryStore and EntryScape

### New Feature

[ENTRYSTORE-302](https://metasolutions.atlassian.net/browse/ENTRYSTORE-302) Add possibility for users to create an account via OpenID login

[ENTRYSTORE-278](https://metasolutions.atlassian.net/browse/ENTRYSTORE-278) Add support for OpenID authentication

[ENTRYSTORE-273](https://metasolutions.atlassian.net/browse/ENTRYSTORE-273) Add support for Statements as entries

[ENTRYSTORE-192](https://metasolutions.atlassian.net/browse/ENTRYSTORE-192) Add SPARCool support

[ENTRYSTORE-145](https://metasolutions.atlassian.net/browse/ENTRYSTORE-145) Support for digest authentication

[ENTRYSTORE-132](https://metasolutions.atlassian.net/browse/ENTRYSTORE-132) Add possibility for users to create an account

[ENTRYSTORE-48](https://metasolutions.atlassian.net/browse/ENTRYSTORE-48) Support for cookie based authentication

[ENTRYSTORE-27](https://metasolutions.atlassian.net/browse/ENTRYSTORE-27) CORE: Plugin interfaces for external communication

[ENTRYSTORE-26](https://metasolutions.atlassian.net/browse/ENTRYSTORE-26) CORE: Implementation of upgrade path from SCAM3

[ENTRYSTORE-25](https://metasolutions.atlassian.net/browse/ENTRYSTORE-25) CORE: Helper methods for interacting with the repository API

[ENTRYSTORE-11](https://metasolutions.atlassian.net/browse/ENTRYSTORE-11) CORE: Import/Export; provide description of upgrade path from SCAM3

### Task

[ENTRYSTORE-331](https://metasolutions.atlassian.net/browse/ENTRYSTORE-331) Ensure that all code-files have license headers

[ENTRYSTORE-330](https://metasolutions.atlassian.net/browse/ENTRYSTORE-330) Document sign-up API

[ENTRYSTORE-312](https://metasolutions.atlassian.net/browse/ENTRYSTORE-312) Move SCAM knowledge base to EntryStore wiki

[ENTRYSTORE-308](https://metasolutions.atlassian.net/browse/ENTRYSTORE-308) Remove support for RDBMS backends

[ENTRYSTORE-295](https://metasolutions.atlassian.net/browse/ENTRYSTORE-295) Change namespaces to entrystore.org

[ENTRYSTORE-283](https://metasolutions.atlassian.net/browse/ENTRYSTORE-283) Test for IPv6 readiness

[ENTRYSTORE-39](https://metasolutions.atlassian.net/browse/ENTRYSTORE-39) Describe the security in the wiki
