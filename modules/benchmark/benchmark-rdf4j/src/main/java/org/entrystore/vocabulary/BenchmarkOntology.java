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
    public static final IRI PERSON;
    public static final IRI SPOUSE;
    public static final IRI ADDRESS;
    public static final IRI COMPANY;
    public static final IRI HAS_ITERATOR;
    public static final IRI HAS_ADDRESS;
    public static final IRI HAS_STREET;
    public static final IRI HAS_CITY;
    public static final IRI HAS_ZIP_CODE;
    public static final IRI HAS_SPOUSE;
    public static final IRI OWNS;
    public static final IRI HAS_LEGAL_NAME;

    static {
        NAMED_GRAPH_PREFIX = createIRI(NAMESPACE, "namedGraph#");
        PERSON = createIRI(NAMESPACE, "Person");
        SPOUSE = createIRI(NAMESPACE, "Spouse");
        ADDRESS = createIRI(NAMESPACE, "Address");
        COMPANY = createIRI(NAMESPACE, "Company");
        HAS_ITERATOR = createIRI(NAMESPACE, "hasIterator");
        HAS_ADDRESS = createIRI(NAMESPACE, "hasAddress");
        HAS_STREET = createIRI(NAMESPACE, "hasStreet");
        HAS_CITY = createIRI(NAMESPACE, "hasCity");
        HAS_ZIP_CODE = createIRI(NAMESPACE, "hasZipCode");
        HAS_SPOUSE = createIRI(NAMESPACE, "hasSpouse");
        OWNS = createIRI(NAMESPACE, "owns");
        HAS_LEGAL_NAME = createIRI(NAMESPACE, "hasLegalName");
    }
}
