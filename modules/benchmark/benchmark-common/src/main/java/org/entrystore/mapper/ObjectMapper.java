package org.entrystore.mapper;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.LogUtils;
import org.entrystore.ResourceType;
import org.entrystore.generator.ObjectGenerator;
import org.entrystore.model.FakeAddress;
import org.entrystore.model.FakeCompany;
import org.entrystore.model.FakeComplexPerson;
import org.entrystore.model.FakePerson;
import org.entrystore.repository.RepositoryException;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.util.List;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;

public class ObjectMapper {

    public static Model populateModelWithPersons(List<Object> persons) {

        ModelBuilder builder = new ModelBuilder().setNamespace(BenchmarkOntology.PREFIX, BenchmarkOntology.NAMESPACE);

        persons.forEach(person -> {
            if (person != null) {
                if (person instanceof FakeComplexPerson) {
                    ObjectMapper.mapComplexPersonToBuilder((FakeComplexPerson) person, builder);
                } else if (person instanceof FakePerson) {
                    ObjectMapper.mapSimplePersonToBuilder((FakePerson) person, builder);
                }
            }
        });

        return builder.build();
    }

    public static Entry mapObjectToContext(Context context, Object object) {

        Entry entry = context.createResource(null, GraphType.None, ResourceType.NamedResource, null);

        Model model = new LinkedHashModel();
        IRI rootIRI = iri(entry.getResourceURI().toString());

        try {
            if (object instanceof FakeAddress) {
                addAddressToModel(model, rootIRI, (FakeAddress) object);
            } else if (object instanceof FakeCompany) {
                IRI addressIRI = null;
                FakeAddress address = ((FakeCompany) object).getAddress();
                if (address != null) {
                    Entry entryAddress = mapObjectToContext(context, address);
                    addressIRI = iri(entryAddress.getResourceURI().toString());
                }

                addCompanyToModel(model, rootIRI, (FakeCompany) object, addressIRI);
            } else if (object instanceof FakeComplexPerson) {
                IRI addressIRI = null;
                FakeAddress address = ((FakePerson) object).getAddress();
                if (address != null) {
                    Entry entryAddress = mapObjectToContext(context, address);
                    addressIRI = iri(entryAddress.getResourceURI().toString());
                }

                IRI companyIRI = null;
                FakeCompany company = ((FakeComplexPerson) object).getCompany();
                if (company != null) {
                    Entry entryCompany = mapObjectToContext(context, company);
                    companyIRI = iri(entryCompany.getResourceURI().toString());
                }

                IRI spouseIRI = null;
                FakeComplexPerson spouse = ((FakeComplexPerson) object).getSpouse();
                if (spouse != null) {
                    Entry entrySpouse = mapObjectToContext(context, spouse);
                    spouseIRI = iri(entrySpouse.getResourceURI().toString());
                }

                addPersonToModel(model, rootIRI, (FakePerson) object, addressIRI, companyIRI, spouseIRI);
            } else if (object instanceof FakePerson) {
                FakeAddress address = ((FakePerson) object).getAddress();
                Entry entryAddress = mapObjectToContext(context, address);
                IRI addressIRI = iri(entryAddress.getResourceURI().toString());

                addPersonToModel(model, rootIRI, (FakePerson) object, addressIRI, null, null);
            }
        } catch (RepositoryException e) {
            e.printStackTrace();
        } finally {
            entry.getLocalMetadata().setGraph(model);
        }

        return entry;
    }

    public static void addNewSimplePersonToList(int i, List<Object> personList) {
        FakePerson person = null;
        while (person == null) {
            try {
                person = ObjectGenerator.createSimplePerson(i);
                personList.add(person);
            } catch (Exception e) {
                LogUtils.log.error(e.getMessage());
            }
        }
    }

    public static void addNewComplexPersonToList(int i, List<Object> personList) {
        FakeComplexPerson person = null;
        while (person == null) {
            try {
                FakeComplexPerson spouse = null;
                while (spouse == null) {
                    try {
                        spouse = ObjectGenerator.createComplexPerson(0, null);
                    } catch (Exception e) {
                        LogUtils.log.error(e.getMessage());
                    }
                }
                person = ObjectGenerator.createComplexPerson(i, spouse);
                personList.add(person);
            } catch (Exception e) {
                LogUtils.log.error(e.getMessage());
            }
        }
    }

    private static void addAddressToModel(Model model, IRI root, FakeAddress address) {
        model.add(root, RDF.TYPE, BenchmarkOntology.ADDRESS);
        model.add(root, BenchmarkOntology.HAS_ITERATOR, literal(address.getIterator()));
        model.add(root, BenchmarkOntology.HAS_STREET, literal(address.getStreet()));
        model.add(root, BenchmarkOntology.HAS_CITY, literal(address.getCity()));
        model.add(root, BenchmarkOntology.HAS_ZIP_CODE, literal(address.getZipCode()));
    }

    private static void addCompanyToModel(Model model, IRI root, FakeCompany company, IRI addressIRI) {
        model.add(root, RDF.TYPE, BenchmarkOntology.COMPANY);
        model.add(root, BenchmarkOntology.HAS_ITERATOR, literal(company.getIterator()));
        model.add(root, BenchmarkOntology.HAS_LEGAL_NAME, literal(company.getLegalName()));
        model.add(root, BenchmarkOntology.HAS_ADDRESS, addressIRI);
    }

    private static void addPersonToModel(Model model, IRI root, FakePerson person, IRI addressIRI, IRI companyIRI, IRI spouseIRI) {
        model.add(root, RDF.TYPE, BenchmarkOntology.PERSON);
        model.add(root, FOAF.FIRST_NAME, literal(person.getFirstName()));
        model.add(root, FOAF.LAST_NAME, literal(person.getLastName()));
        model.add(root, BenchmarkOntology.HAS_ITERATOR, literal(person.getIterator()));
        if (addressIRI != null) {
            model.add(root, BenchmarkOntology.HAS_ADDRESS, addressIRI);
        }
        if (companyIRI != null) {
            model.add(root, BenchmarkOntology.OWNS, companyIRI);
        }
        if (spouseIRI != null) {
            model.add(root, BenchmarkOntology.HAS_SPOUSE, spouseIRI);
        }
    }

    private static void mapSimplePersonToBuilder(FakePerson person, ModelBuilder builder) {

        FakeAddress address = person.getAddress();

        builder
                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.PERSON)
                .add(FOAF.FIRST_NAME, person.getFirstName())
                .add(FOAF.LAST_NAME, person.getLastName())
                .add(BenchmarkOntology.HAS_ITERATOR, person.getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + address.getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(address.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + address.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ADDRESS)
                .add(BenchmarkOntology.HAS_ITERATOR, address.getIterator())
                .add(BenchmarkOntology.HAS_STREET, address.getStreet())
                .add(BenchmarkOntology.HAS_CITY, address.getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, address.getZipCode());
    }

    private static void mapComplexPersonToBuilder(FakeComplexPerson person, ModelBuilder builder) {

        FakeAddress address = person.getAddress();
        FakeComplexPerson spouse = person.getSpouse();
        FakeCompany company = person.getCompany();

        builder
                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.PERSON)
                .add(FOAF.FIRST_NAME, person.getFirstName())
                .add(FOAF.LAST_NAME, person.getLastName())
                .add(FOAF.AGE, person.getAge())
                .add(FOAF.PHONE, person.getPhoneNumber())
                .add(BenchmarkOntology.HAS_ITERATOR, person.getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + address.getIdentifier())
                .add(BenchmarkOntology.OWNS, BenchmarkOntology.INDIVIDUAL_PREFIX + company.getIdentifier())
                .add(BenchmarkOntology.HAS_SPOUSE, BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(address.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + address.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ADDRESS)
                .add(BenchmarkOntology.HAS_ITERATOR, address.getIterator())
                .add(BenchmarkOntology.HAS_STREET, address.getStreet())
                .add(BenchmarkOntology.HAS_CITY, address.getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, address.getZipCode())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(company.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + company.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.COMPANY)
                .add(BenchmarkOntology.HAS_LEGAL_NAME, company.getLegalName())
                .add(BenchmarkOntology.HAS_ITERATOR, company.getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + company.getAddress().getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(company.getAddress().getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + company.getAddress().getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ADDRESS)
                .add(BenchmarkOntology.HAS_ITERATOR, company.getAddress().getIterator())
                .add(BenchmarkOntology.HAS_STREET, company.getAddress().getStreet())
                .add(BenchmarkOntology.HAS_CITY, company.getAddress().getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, company.getAddress().getZipCode())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(spouse.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.SPOUSE)
                .add(FOAF.FIRST_NAME, spouse.getFirstName())
                .add(FOAF.LAST_NAME, spouse.getLastName())
                .add(FOAF.AGE, spouse.getAge())
                .add(FOAF.PHONE, spouse.getPhoneNumber())
                .add(BenchmarkOntology.HAS_ITERATOR, spouse.getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getAddress().getIdentifier())
                .add(BenchmarkOntology.OWNS, BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getCompany().getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(spouse.getAddress().getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getAddress().getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ADDRESS)
                .add(BenchmarkOntology.HAS_ITERATOR, spouse.getAddress().getIterator())
                .add(BenchmarkOntology.HAS_STREET, spouse.getAddress().getStreet())
                .add(BenchmarkOntology.HAS_CITY, spouse.getAddress().getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, spouse.getAddress().getZipCode())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(spouse.getCompany().getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getCompany().getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.COMPANY)
                .add(BenchmarkOntology.HAS_LEGAL_NAME, spouse.getCompany().getLegalName())
                .add(BenchmarkOntology.HAS_ITERATOR, spouse.getCompany().getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getCompany().getAddress().getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(spouse.getCompany().getAddress().getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + spouse.getCompany().getAddress().getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ADDRESS)
                .add(BenchmarkOntology.HAS_ITERATOR, spouse.getCompany().getAddress().getIterator())
                .add(BenchmarkOntology.HAS_STREET, spouse.getCompany().getAddress().getStreet())
                .add(BenchmarkOntology.HAS_CITY, spouse.getCompany().getAddress().getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, spouse.getCompany().getAddress().getZipCode());
    }
}
