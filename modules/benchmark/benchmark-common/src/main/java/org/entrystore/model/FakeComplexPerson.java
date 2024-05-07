package org.entrystore.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@Setter
public class FakeComplexPerson extends FakePerson {
    int age;
    String phoneNumber;
    FakeComplexPerson spouse;
    FakeCompany company;
}
