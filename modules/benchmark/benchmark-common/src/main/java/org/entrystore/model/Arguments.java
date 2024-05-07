package org.entrystore.model;

import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class Arguments {
    String storeType;
    int sizeToGenerate = 0;
    boolean isComplex = false;
    boolean withTransactions = false;
    boolean withInterRequests = false;
    int interRequestsModulo = -1;
}
