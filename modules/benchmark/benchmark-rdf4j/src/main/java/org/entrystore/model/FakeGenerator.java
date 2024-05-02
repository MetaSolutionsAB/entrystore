package org.entrystore.model;

import com.github.javafaker.Address;
import com.github.javafaker.Company;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.LogUtils;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public class FakeGenerator {

    private static final Faker faker = new Faker();
    private static final Random random = new Random();

    public static List<FakePerson> createSimplePersonList(int size) {
        List<FakePerson> list = new ArrayList<>();

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

    public static List<FakeComplexPerson> createComplexPersonList(int size) {
        List<FakeComplexPerson> list = new ArrayList<>();

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

    public static void mapSimplePersonToBuilder(FakePerson person, ModelBuilder builder) {

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

    public static void mapComplexPersonToBuilder(FakeComplexPerson person, ModelBuilder builder) {

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
