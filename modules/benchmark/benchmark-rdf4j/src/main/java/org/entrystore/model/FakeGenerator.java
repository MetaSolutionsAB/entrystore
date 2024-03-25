package org.entrystore.model;

import com.github.javafaker.Address;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import org.entrystore.LogUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

public class FakeGenerator {

    Faker faker = new Faker();

    public FakePerson createPerson(int i) {
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

    public List<FakePerson> createPersonListParallel(int size) {
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

    public FakeAddress createAddress(int i) {
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
