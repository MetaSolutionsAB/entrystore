package org.entrystore.generator;

import com.github.javafaker.Address;
import com.github.javafaker.Company;
import com.github.javafaker.Faker;
import com.github.javafaker.Name;
import org.entrystore.mapper.ObjectMapper;
import org.entrystore.model.FakeAddress;
import org.entrystore.model.FakeCompany;
import org.entrystore.model.FakeComplexPerson;
import org.entrystore.model.FakePerson;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.IntStream;

public class ObjectGenerator {

	private static final Faker faker = new Faker();
	private static final Random random = new Random();

	// generate list of Persons with Addresses, Spouses and Companies. Spouse has also a Company and Address. Company has also an Address.
	// OR generate list of Persons with Addresses, 1 Person has exactly 1 Address
	public static List<Object> createPersonList(int size, boolean isComplex) {
		List<Object> list = new ArrayList<>();

		while (list.size() < size) {
			IntStream.range(list.size(), size)
					.parallel()
					.forEach(i -> {
						if (isComplex) {
							ObjectMapper.addNewComplexPersonToList(i, list);
						} else {
							ObjectMapper.addNewSimplePersonToList(i, list);
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

	private static FakeCompany createCompany(int i) {
		Company company = faker.company();
		FakeAddress address = createAddress(0);

		return FakeCompany.builder()
				.iterator(i)
				.identifier(Math.abs(company.name().hashCode()))
				.legalName(company.name())
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

}
