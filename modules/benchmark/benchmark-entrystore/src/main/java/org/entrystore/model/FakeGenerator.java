package org.entrystore.model;

import com.github.javafaker.Address;
import com.github.javafaker.Company;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.Context;
import org.entrystore.Entry;
import org.entrystore.GraphType;
import org.entrystore.LogUtils;
import org.entrystore.ResourceType;
import org.entrystore.repository.RepositoryException;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

import static org.eclipse.rdf4j.model.util.Values.iri;
import static org.eclipse.rdf4j.model.util.Values.literal;

public class FakeGenerator {

    private static final Faker faker = new Faker();
    private static final Random random = new Random();

    public static List<Object> createSimplePersonList(int size) {
        List<Object> list = new ArrayList<>();

        while (list.size() < size) {
            IntStream.range(list.size(), size)
                    .parallel()
                    .forEach(i -> {
                        FakePerson person = null;
                        while (person == null) {
                            try {
                                person = createSimplePerson(i);
                                list.add(person);
                            } catch (Exception e) {
                                LogUtils.log.error(e.getMessage());
                            }
                        }
                    });
        }

        return list;
    }

    public static List<Object> createComplexPersonList(int size) {
        List<Object> list = new ArrayList<>();

        while (list.size() < size) {
            IntStream.range(list.size(), size)
                    .parallel()
                    .forEach(i -> {
                        FakeComplexPerson person = null;
                        while (person == null) {
                            try {
                                FakeComplexPerson spouse = null;
                                while (spouse == null) {
                                    try {
                                        spouse = createComplexPerson(0, null);
                                    } catch (Exception e) {
                                        LogUtils.log.error(e.getMessage());
                                    }
                                }
                                person = createComplexPerson(i, spouse);
                                list.add(person);
                            } catch (Exception e) {
                                LogUtils.log.error(e.getMessage());
                            }
                        }
                    });
        }

        return list;
    }

    public static FakePerson createSimplePerson(int i) {
        Name name = faker.name();
        FakeAddress address = createAddress(i);

        return FakePerson.builder()
                .iterator(i)
                .identifier(Math.abs(name.firstName().hashCode()))
                .firstName(name.firstName())
                .lastName(name.lastName())
                .address(address)
                .build();
    }

    public static FakeComplexPerson createComplexPerson(int i, FakeComplexPerson spouse) {
        Name name = faker.name();
        FakeAddress address = createAddress(i);
        FakeCompany company = createCompany(i);

        if (spouse != null) {
            return FakeComplexPerson.builder()
                    .iterator(i)
                    .identifier(Math.abs(name.firstName().hashCode()))
                    .firstName(name.firstName())
                    .lastName(name.lastName())
                    .age(random.nextInt(100 - 15) + 15)
                    .phoneNumber(faker.phoneNumber().cellPhone())
                    .address(address)
                    .company(company)
                    .spouse(spouse)
                    .build();
        } else {
            return FakeComplexPerson.builder()
                    .iterator(i)
                    .identifier(Math.abs(name.firstName().hashCode()))
                    .firstName(name.firstName())
                    .lastName(name.lastName())
                    .age(random.nextInt(100 - 15) + 15)
                    .phoneNumber(faker.phoneNumber().cellPhone())
                    .address(address)
                    .company(company)
                    .build();
        }
    }

    private static FakeAddress createAddress(int i) {
        Address address = faker.address();

        return FakeAddress.builder()
                .iterator(i)
                .identifier(Math.abs(address.streetAddress().hashCode()))
                .street(address.streetAddress())
                .city(address.city())
                .zipCode(address.zipCode())
                .build();
    }

    public static FakeCompany createCompany(int i) {
        Company company = faker.company();
        FakeAddress address = createAddress(0);

        return FakeCompany.builder()
                .iterator(i)
                .identifier(Math.abs(company.name().hashCode()))
                .legalName(company.name())
                .address(address)
                .build();
    }

    public static Entry mapObjectToContext(Context context, Object object) {

        Entry entry = context.createResource(null, GraphType.None, ResourceType.NamedResource, null);

        Model model = entry.getLocalMetadata().getGraph();
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

    private static void addPersonToModel(
            Model model, IRI root,
            FakePerson person,
            IRI addressIRI,
            IRI companyIRI,
            IRI spouseIRI) {
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
}
