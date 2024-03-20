package org.entrystore.model;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class FakePerson {
    int identifier;
    int iterator;
    String firstName;
    String lastName;
    FakeAddress address;
}
