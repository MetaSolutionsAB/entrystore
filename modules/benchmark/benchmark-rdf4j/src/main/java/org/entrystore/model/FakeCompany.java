package org.entrystore.model;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class FakeCompany {
    int identifier;
    int iterator;
    String legalName;
    FakeAddress address;
}
