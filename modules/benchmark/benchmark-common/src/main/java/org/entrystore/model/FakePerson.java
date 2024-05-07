package org.entrystore.model;

import lombok.Getter;
import lombok.Setter;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@Setter
public class FakePerson {
    int identifier;
    int iterator;
    String firstName;
    String lastName;
    FakeAddress address;
}
