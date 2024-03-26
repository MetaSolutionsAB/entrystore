package org.entrystore.model;

import com.github.javafaker.Address;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.model.vocabulary.FOAF;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.entrystore.LogUtils;
import org.entrystore.vocabulary.BenchmarkOntology;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class FakeGenerator {

    private static final Faker faker = new Faker();

    public static List<FakePerson> createPersonList(int size) {
        List<FakePerson> list = new ArrayList<>();

        IntStream.range(0, size)
                .parallel()
                .forEach(i -> {
                    try {
                        list.add(createPerson(i));
                    } catch (Exception e) {
                        LogUtils.log.error(e.getMessage());
                    }
                });

        return list;
    }

    private static FakePerson createPerson(int i) {
        Name name = faker.name();
        FakeAddress address = createAddress(i);

        return new FakePerson.FakePersonBuilder()
                .iterator(i)
                .identifier(Math.abs(name.firstName().hashCode()))
                .firstName(name.firstName())
                .lastName(name.lastName())
                .address(address)
                .build();
    }

    private static FakeAddress createAddress(int i) {
        Address address = faker.address();

        return new FakeAddress.FakeAddressBuilder()
                .iterator(i)
                .identifier(Math.abs(address.streetAddress().hashCode()))
                .street(address.streetAddress())
                .city(address.city())
                .zipCode(address.zipCode())
                .build();
    }

    public static void mapToBuilder(FakePerson person, ModelBuilder builder) {

        builder
                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getIdentifier())
                .add(RDF.TYPE, BenchmarkOntology.ARTIST)
                .add(FOAF.FIRST_NAME, person.getFirstName())
                .add(FOAF.LAST_NAME, person.getLastName())
                .add(BenchmarkOntology.HAS_ITERATOR, person.getIterator())
                .add(BenchmarkOntology.HAS_ADDRESS, BenchmarkOntology.INDIVIDUAL_PREFIX + person.getAddress().getIdentifier())

                .namedGraph(BenchmarkOntology.NAMED_GRAPH_PREFIX + String.valueOf(person.getAddress().getIdentifier()))
                .subject(BenchmarkOntology.INDIVIDUAL_PREFIX + person.getAddress().getIdentifier())
                .add(BenchmarkOntology.HAS_ITERATOR, person.getAddress().getIterator())
                .add(BenchmarkOntology.HAS_STREET, person.getAddress().getStreet())
                .add(BenchmarkOntology.HAS_CITY, person.getAddress().getCity())
                .add(BenchmarkOntology.HAS_ZIP_CODE, person.getAddress().getZipCode());
    }
}
