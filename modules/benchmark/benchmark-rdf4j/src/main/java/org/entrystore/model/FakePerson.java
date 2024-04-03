package org.entrystore.model;

import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

@Builder
@Getter
@Setter
public class FakePerson {
    int identifier;
    int iterator;
    String firstName;
    String lastName;
    FakeAddress address;
}
