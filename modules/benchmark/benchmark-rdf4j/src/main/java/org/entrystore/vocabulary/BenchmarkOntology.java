package org.entrystore.vocabulary;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.base.InternedIRI;

public class BenchmarkOntology {


    private static IRI createIRI(String namespace, String localName) {
        return new InternedIRI(namespace, localName);
    }
    public static final String NAMESPACE = "http://www.example.org/";
    public static final String PREFIX = "ex:";
    public static final String INDIVIDUAL_PREFIX = PREFIX + "#";
    public static final IRI NAMED_GRAPH_PREFIX;
    public static final IRI ARTIST;
    public static final IRI HAS_ITERATOR;
    public static final IRI HAS_ADDRESS;
    public static final IRI HAS_STREET;
    public static final IRI HAS_CITY;
    public static final IRI HAS_ZIP_CODE;

    static {
        NAMED_GRAPH_PREFIX = createIRI(NAMESPACE, "namedGraph#");
        ARTIST = createIRI(NAMESPACE, "Artist");
        HAS_ITERATOR = createIRI(NAMESPACE, "hasIterator");
        HAS_ADDRESS = createIRI(NAMESPACE, "hasAddress");
        HAS_STREET = createIRI(NAMESPACE, "hasStreet");
        HAS_CITY = createIRI(NAMESPACE, "hasCity");
        HAS_ZIP_CODE = createIRI(NAMESPACE, "hasZipCode");
    }
}
