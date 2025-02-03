package org.entrystore.rest.it.util

class NameSpaceConst {
	static ES_TERMS = 'http://entrystore.org/terms/'
	static DC_TERMS = 'http://purl.org/dc/terms/'
	static DC_ELEMENTS = 'http://purl.org/dc/elements/1.1/'
	static RDF = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#'
	static XSD = 'http://www.w3.org/2001/XMLSchema#'

	static RDF_TYPE = RDF + 'type'

	static TERM_CONTEXT = ES_TERMS + 'Context'
	static TERM_STRING = ES_TERMS + 'String'
	static TERM_USER = ES_TERMS + 'User'
	static TERM_GROUP = ES_TERMS + 'Group'
	static TERM_LINK = ES_TERMS + 'Link'
	static TERM_REFERENCE = ES_TERMS + 'Reference'
	static TERM_LINK_REFERENCE = ES_TERMS + 'LinkReference'
	static TERM_LIST = ES_TERMS + 'List'
	static TERM_RESOURCE = ES_TERMS + 'resource'
	static TERM_METADATA = ES_TERMS + 'metadata'
	static TERM_EXTERNAL_METADATA = ES_TERMS + 'externalMetadata'
	static TERM_CACHED_EXTERNAL_METADATA = ES_TERMS + 'cachedExternalMetadata'
	static TERM_HOME_CONTEXT = ES_TERMS + 'homeContext'
	static TERM_NAMED_RESOURCE = ES_TERMS + 'NamedResource'

	static DC_TERM_TITLE = DC_TERMS + 'title'
	static DC_TERM_CREATOR = DC_TERMS + 'creator'
	static DC_TERM_PUBLISHER = DC_TERMS + 'publisher'
	static DC_TERM_SUBJECT = DC_TERMS + 'subject'
	static DC_TERM_DESCRIPTION = DC_TERMS + 'description'

	static NS_DCAT_DATASET = 'http://www.w3.org/ns/dcat#Dataset'
}
