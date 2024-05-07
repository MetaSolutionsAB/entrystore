package org.entrystore.model;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class FakeAddress {
    int identifier;
    int iterator;
    String street;
    String city;
    String zipCode;
}
