package org.entrystore.model;

import com.github.javafaker.Address;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;

import java.util.ArrayList;
import java.util.List;

public class FakeGenerator {

    public FakePerson createPerson(int i) {
        Faker faker = new Faker();
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

    public List<FakePerson> createPersonList(int size) {
        List<FakePerson> list = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            list.add(createPerson(i));
        }

        return list;
    }

    public FakeAddress createAddress(int i) {
        Faker faker = new Faker();
        Address address = faker.address();

        return new FakeAddress.FakeAddressBuilder()
                .iterator(i)
                .identifier(Math.abs(address.streetAddress().hashCode()))
                .street(address.streetAddress())
                .city(address.city())
                .zipCode(address.zipCode())
                .build();
    }

    public List<FakeAddress> createAddressList(int size) {
        List<FakeAddress> list = new ArrayList<>();

        for (int i = 0; i < size; i++) {
            list.add(createAddress(i));
        }

        return list;
    }
}
